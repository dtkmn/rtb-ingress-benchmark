import asyncio
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.pipeline import AdmissionRejected, BoundedPipeline
from app.models import BidRequest


class HeldTransport:
    def __init__(self, fail=False, stop_held=False):
        self.started = asyncio.Event()
        self.release = asyncio.Event()
        self.stop_release = asyncio.Event()
        self.calls = []
        self.fail = fail
        self.stop_held = stop_held

    async def start(self):
        pass

    async def stop(self):
        if self.stop_held:
            await self.stop_release.wait()

    async def publish(self, **record):
        self.calls.append(record)
        self.started.set()
        await self.release.wait()
        if self.fail:
            raise RuntimeError("injected SDK failure")


def balanced(p):
    s = p.snapshot()
    assert s["admission_candidates"] == s["admitted"] + s["admission_rejected"] + s["admission_undecided"]
    assert s["admitted"] == s["client_succeeded"] + s["client_failed"] + s["abandoned_unknown"] + s["outstanding"]


def test_immutable_owned_receipt_deadline_and_duplicate_settlement():
    async def run():
        transport = HeldTransport()
        p = BoundedPipeline(Settings(), transport)
        p.ready = True
        value, key = bytearray(b"original"), bytearray(b"id")
        receipt = p.commit(p.candidate(), topic="bids", key=key, value=value)
        value[:] = b"modified"
        key[:] = b"xx"
        await transport.started.wait()
        assert transport.calls[0]["payload"] == b"original"
        assert transport.calls[0]["key"] == b"id"
        assert not await p.confirm(replace(receipt, deadline=asyncio.get_running_loop().time() - 1))
        assert p.snapshot()["outstanding"] == 1
        assert p.snapshot()["outstanding_bytes"] == 10
        assert p.snapshot()["client_failed"] == 0
        transport.release.set()
        assert await receipt.outcome
        assert not p.settle(receipt.attempt, True)
        assert p.snapshot()["client_succeeded"] == 1
        assert p.snapshot()["outstanding_bytes"] == 0
        balanced(p)
        await p.shutdown()
    asyncio.run(run())


def test_record_race_and_byte_limit_reject_without_dispatch():
    async def run():
        transport = HeldTransport()
        p = BoundedPipeline(Settings(pipeline_max_records=1, pipeline_max_bytes=4), transport)
        p.ready = True
        candidates = [p.candidate(), p.candidate()]
        async def compete(candidate):
            try:
                return p.commit(candidate, topic="bids", key=b"x", value=b"123")
            except AdmissionRejected:
                return None
        receipts = await asyncio.gather(*(compete(c) for c in candidates))
        assert sum(r is not None for r in receipts) == 1
        assert p.snapshot()["admitted"] == 1
        assert p.snapshot()["admission_rejected"] == 1
        transport.release.set()
        await next(r.outcome for r in receipts if r is not None)
        with pytest.raises(AdmissionRejected, match="byte_capacity"):
            p.commit(p.candidate(), topic="bids", key=b"x", value=b"1234")
        assert len(transport.calls) == 1
        balanced(p)
        await p.shutdown()
    asyncio.run(run())


def test_precommit_fencing_and_postcommit_sdk_failure():
    async def run():
        transport = HeldTransport(fail=True)
        p = BoundedPipeline(Settings(), transport)
        with pytest.raises(AdmissionRejected, match="unavailable"):
            p.commit(p.candidate(), topic="bids", key=b"id", value=b"v")
        p.ready = True
        candidate = p.candidate()
        p.reject(candidate, "deadline")
        with pytest.raises(AdmissionRejected, match="fenced"):
            p.commit(candidate, topic="bids", key=b"id", value=b"v")
        receipt = p.commit(p.candidate(), topic="bids", key=b"id", value=b"v")
        assert p.snapshot()["admitted"] == 1
        transport.release.set()
        assert not await receipt.outcome
        assert p.snapshot()["client_failed"] == 1
        assert p.snapshot()["admission_rejected"] == 2
        balanced(p)
        await p.shutdown()
    asyncio.run(run())


def test_drain_requires_teardown_before_unknown_and_retains_preparation():
    async def run():
        transport = HeldTransport(stop_held=True)
        p = BoundedPipeline(Settings(drain_timeout_ms=1, preparation_max_requests=1), transport)
        p.ready = True
        lease = p.prepare()
        assert p.prepare() is None
        receipt = p.commit(p.candidate(), topic="bids", key=b"id", value=b"v")
        result = await p.shutdown(teardown_timeout=0.001)
        assert result["teardown_confirmed"] is False
        assert result["outstanding"] == 1 and result["outstanding_bytes"] == 3
        assert result["abandoned_unknown"] == 0
        assert result["preparation_count"] == 1
        lease.release()
        transport.stop_release.set()
        result = await p.shutdown()
        assert result["teardown_confirmed"] is True
        assert result["abandoned_unknown"] == 1
        assert result["outstanding"] == 0 and result["preparation_count"] == 0
        assert not await receipt.outcome
        balanced(p)
    asyncio.run(run())


def test_container_budgets_are_not_multiplied_by_workers():
    p = BoundedPipeline(Settings(workers=3), None)
    assert p.max_records * 3 <= 1024
    assert p.max_bytes * 3 <= 16777216
    assert p.max_preparations * 3 <= 128
    with pytest.raises(ValueError):
        BoundedPipeline(Settings(workers=129), None)


def test_http_only_body_limit_and_preparation_rejection():
    app = create_app(settings=Settings(delivery_mode="http-only", preparation_max_requests=1))
    with TestClient(app) as client:
        assert client.post("/bid-request", content=b" " * 65537, headers={"content-type": "application/json"}).status_code == 413
        # Hold the same real permit the ASGI gate requires, before any parsing.
        lease = client.portal.call(app.state.pipeline.prepare)
        assert client.post("/bid-request", content=b"invalid", headers={"content-type": "application/json"}).status_code == 503
        client.portal.call(lease.release)
        state = client.get("/benchmark/state").json()
        assert state["preparation_rejected"] == 1
        assert state["admission_candidates"] == 0
        assert state["preparation_count"] == 0


def test_confirm_ack_zero_is_rejected_at_startup():
    app = create_app(settings=Settings(kafka_acks=0), bootstrap_publisher=False)
    with pytest.raises(ValueError, match="acknowledgements"):
        with TestClient(app):
            pass


@pytest.mark.parametrize("mode,status", [("enqueue", 200), ("confirm", 503)])
def test_http_response_does_not_release_held_client_work(mode, status):
    transport = HeldTransport()
    app = create_app(settings=Settings(delivery_mode=mode, http_outcome_timeout_ms=1), publisher=transport)
    with TestClient(app) as client:
        response = client.post("/bid-request", json={"id": "same-id", "site": {}, "device": {}})
        assert response.status_code == status
        state = client.get("/benchmark/state").json()
        assert state["outstanding"] == 1 and state["outstanding_bytes"] > 0
        assert state["client_failed"] == 0 and state["preparation_count"] == 0
        async def finish():
            transport.release.set()
            await asyncio.gather(*list(app.state.pipeline.tasks.values()))
        client.portal.call(finish)
        state = client.get("/benchmark/state").json()
        assert state["client_succeeded"] == 1 and state["outstanding"] == 0


def test_serialization_failure_is_precommit_rejection(monkeypatch):
    def fail(*_args, **_kwargs):
        raise RuntimeError("injected serializer failure")
    monkeypatch.setattr(BidRequest, "model_dump_json", fail)
    transport = HeldTransport()
    app = create_app(settings=Settings(delivery_mode="enqueue"), publisher=transport)
    with TestClient(app) as client:
        assert client.post("/bid-request", json={"id": "id", "site": {}, "device": {}}).status_code == 500
        state = client.get("/benchmark/state").json()
        assert state["admission_candidates"] == 1 and state["admission_rejected"] == 1
        assert state["admitted"] == 0 and state["admission_undecided"] == 0
        assert transport.calls == []
