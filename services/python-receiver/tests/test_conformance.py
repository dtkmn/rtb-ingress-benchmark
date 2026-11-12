import asyncio
import base64
import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

CORPUS = json.loads((Path(__file__).parents[3] / "tests/conformance/corpus.json").read_text())
FAULTS = [case for case in CORPUS["cases"] if case.get("scenario")]
TOKEN = {"x-conformance-token": "focused-test-token"}


@pytest.mark.parametrize("mode", ["http-only", "enqueue", "confirm"])
def test_all_fault_cases_use_real_handler_and_default_capacity_pipeline(monkeypatch, mode):
    monkeypatch.setenv("BENCHMARK_CONFORMANCE", "1")
    monkeypatch.setenv("BENCHMARK_CONFORMANCE_TOKEN", TOKEN["x-conformance-token"])

    async def run():
        app = create_app(settings=Settings(delivery_mode=mode, http_outcome_timeout_ms=80, drain_timeout_ms=30))
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://fixture") as client:
                async def control(action, **kwargs):
                    response = await client.post("/benchmark/conformance", headers=TOKEN, json={"action": action, **kwargs})
                    assert response.status_code == 200, response.text
                    return response.json()

                async def observed(predicate):
                    async def wait():
                        while True:
                            state = (await client.get("/benchmark/conformance", headers=TOKEN)).json()
                            if predicate(state):
                                return state
                            await asyncio.sleep(0.001)
                    return await asyncio.wait_for(wait(), 2)

                ordered = sorted(FAULTS, key=lambda case: case["scenario"]["fault"] == "drain_deadline_unsettled")
                for case in ordered:
                    fault = case["scenario"]["fault"]
                    if fault == "confirm_ack_zero" and mode == "confirm":
                        continue  # actual unsupported startup boundary is tested separately below
                    configured = await control("configure", case_id=case["id"], fault=fault)
                    baseline = configured["baseline"]
                    raw = case["request"]["body"]["data"].encode()
                    tasks = [asyncio.create_task(client.post("/bid-request", content=raw, headers={"content-type": "application/json"}))]
                    race = fault == "final_slot_race" and mode != "http-only"
                    if race:
                        tasks.append(asyncio.create_task(client.post("/bid-request", content=raw.replace(b"req-1-0", b"req-1-1"), headers={"content-type": "application/json"})))
                    if mode != "http-only" and fault in {"held_admission_then_success", "held_admission_then_rejection", "admission_deadline_then_late_receipt", "final_slot_race"}:
                        state = await observed(lambda state: state["held_admission"] == (2 if race else 1))
                        assert not any(task.done() for task in tasks)
                        assert state["state"]["admission_undecided"] == len(tasks)
                        if fault == "admission_deadline_then_late_receipt":
                            assert (await tasks[0]).status_code == 503
                            held = await observed(lambda state: state["state"]["admission_undecided"] == 0)
                            assert held["state"]["preparation_count"] - baseline["preparation_count"] == 1
                            assert held["state"]["admission_rejected"] - baseline["admission_rejected"] == 1
                            assert held["state"]["outstanding"] == baseline["outstanding"]
                            state = await control("release_admission")
                            assert state["state"]["preparation_count"] == baseline["preparation_count"]
                            assert state["deadline_elapsed"]
                            assert any(event["name"] == "late_receipt_fenced" for event in state["events"])
                        else:
                            await control("reject_admission" if fault == "held_admission_then_rejection" else "release_admission")
                    holds_delivery = mode != "http-only" and fault in {"held_admission_then_success", "held_delivery_then_success", "delivery_failure_after_receipt", "response_deadline_then_late_success", "final_slot_race", "drain_deadline_unsettled", "duplicate_completion_callback", "source_mutation_after_receipt"}
                    if holds_delivery:
                        state = await observed(lambda state: state["held_delivery"] == 1)
                        assert state["state"]["outstanding"] - baseline["outstanding"] == 1
                        assert state["state"]["client_failed"] - baseline["client_failed"] == 0
                        if mode == "enqueue":
                            await observed(lambda _state: any(task.done() and task.result().status_code == 200 for task in tasks))
                        else:
                            assert any(not task.done() for task in tasks)
                        if fault == "source_mutation_after_receipt":
                            assert state["records"] == []
                            changed = await control("mutate_source")
                            assert changed["mutation"]["performed"]
                            assert changed["mutation"]["before_value_base64"] != changed["mutation"]["after_value_base64"]
                        if fault == "response_deadline_then_late_success":
                            await observed(lambda state: state["deadline_elapsed"])
                            assert (await tasks[0]).status_code == (503 if mode == "confirm" else 200)
                            await control("release_delivery")
                        elif fault == "drain_deadline_unsettled":
                            drained = await control("drain")
                            assert drained["drain"]["before"]["outstanding"] == 1
                            assert drained["drain"]["after"]["abandoned_unknown"] == 1
                            assert drained["drain"]["after"]["drain_result"] == "timed_out"
                            assert drained["drain"]["after"]["teardown_confirmed"]
                            teardown = next(event for event in drained["events"] if event["name"] == "teardown_confirmed")
                            assert teardown["state"]["outstanding"] == 1 and teardown["state"]["abandoned_unknown"] == 0
                        else:
                            await control("fail_delivery" if fault == "delivery_failure_after_receipt" else "release_delivery")
                        if fault != "drain_deadline_unsettled":
                            await observed(lambda state: state["state"]["outstanding"] == baseline["outstanding"])
                        if fault == "duplicate_completion_callback":
                            duplicate = await control("duplicate_completion")
                            event = next(event for event in duplicate["events"] if event["name"] == "duplicate_completion")
                            assert event["applied"] is False and event["before"] == event["after"]
                    responses = await asyncio.gather(*tasks)
                    expected = case["expectations"][mode]
                    if race:
                        assert sorted(response.status_code for response in responses) == [200, 503]
                    else:
                        assert responses[0].status_code == expected["status"], (case["id"], mode)
                    state = await observed(lambda state: state["state"]["preparation_count"] == baseline["preparation_count"])
                    delta = {field: state["state"][field] - baseline[field] for field in expected["accounting"]}
                    if race:
                        # Exclude the actually observed winning receipt, never choose a loser.
                        for field in ("admission_candidates", "admitted", "client_succeeded"):
                            delta[field] -= 1
                        assert len(state["records"]) == 1
                        assert configured["setup"]["records"] == baseline["outstanding"] == 1023
                    assert delta == expected["accounting"], (case["id"], mode, delta)
                    if expected.get("record"):
                        assert len(state["records"]) == 1
                        assert base64.b64decode(state["records"][0]["key_base64"]).decode() == expected["record"]["key"]
                        assert json.loads(base64.b64decode(state["records"][0]["value_base64"])) == expected["record"]["value"]
                    if fault == "backlog_byte_limit" and mode != "http-only":
                        assert configured["setup"]["bytes"] == 16777215
                        assert configured["setup"]["records"] == 256
                    if fault == "preparation_limit":
                        assert configured["setup"]["preparations"] == 128
                    if fault != "drain_deadline_unsettled":
                        cleaned = await control("cleanup")
                        assert cleaned["state"]["outstanding"] == cleaned["state"]["preparation_count"] == 0
                        assert cleaned["state"]["admission_candidates"] == cleaned["state"]["admitted"] + cleaned["state"]["admission_rejected"]
                runtime = (await client.get("/benchmark/runtime")).json()
                assert runtime["producer"]["instances"] == 0
                assert runtime["producer"]["validation_transport"] is (mode != "http-only")
    asyncio.run(run())


def test_disabled_unauthorized_and_missing_token_do_not_parse_commands(monkeypatch):
    monkeypatch.delenv("BENCHMARK_CONFORMANCE", raising=False)
    app = create_app(settings=Settings(delivery_mode="http-only"))
    assert app.state.conformance is None
    with TestClient(app) as client:
        assert client.post("/benchmark/conformance", content="not json", headers=TOKEN).status_code == 404
    monkeypatch.setenv("BENCHMARK_CONFORMANCE", "1")
    monkeypatch.delenv("BENCHMARK_CONFORMANCE_TOKEN", raising=False)
    with pytest.raises(ValueError, match="nonempty token"):
        create_app(settings=Settings(delivery_mode="http-only"))
    monkeypatch.setenv("BENCHMARK_CONFORMANCE_TOKEN", TOKEN["x-conformance-token"])
    app = create_app(settings=Settings(delivery_mode="http-only"))
    with TestClient(app) as client:
        assert client.post("/benchmark/conformance", content="not json").status_code == 404
        assert client.post("/benchmark/conformance", content="not json", headers={"x-conformance-token":"wrong"}).status_code == 404
        assert client.post("/benchmark/conformance", content=b"x" * 8193, headers=TOKEN).status_code == 413
        assert client.get("/benchmark/conformance", headers=TOKEN).json()["case_id"] is None


def test_actual_confirm_ack_zero_startup_remains_unsupported(monkeypatch):
    monkeypatch.setenv("BENCHMARK_CONFORMANCE", "1")
    monkeypatch.setenv("BENCHMARK_CONFORMANCE_TOKEN", TOKEN["x-conformance-token"])
    app = create_app(settings=Settings(delivery_mode="confirm", kafka_acks=0))
    with pytest.raises(ValueError, match="confirm does not support"):
        with TestClient(app):
            pass


@pytest.mark.parametrize("after_receipt", [False, True])
def test_cancelled_handler_rejects_only_undecided_admission_and_retains_owned_work(monkeypatch, after_receipt):
    monkeypatch.setenv("BENCHMARK_CONFORMANCE", "1")
    monkeypatch.setenv("BENCHMARK_CONFORMANCE_TOKEN", TOKEN["x-conformance-token"])
    async def run():
        app = create_app(settings=Settings(delivery_mode="confirm", http_outcome_timeout_ms=1000))
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://fixture") as client:
                fault = "held_delivery_then_success" if after_receipt else "held_admission_then_success"
                await client.post("/benchmark/conformance", headers=TOKEN, json={"action":"configure", "case_id":"cancel-test", "fault":fault})
                request = asyncio.create_task(client.post("/bid-request", json={"id":"cancel-test", "site":{}, "device":{}}))
                controller = app.state.conformance
                while not (controller.held_delivery if after_receipt else controller.held_admission):
                    await asyncio.sleep(0)
                if after_receipt:
                    frames = []
                    for task in asyncio.all_tasks():
                        coroutine = task.get_coro()
                        while coroutine is not None:
                            frame = getattr(coroutine, "cr_frame", None)
                            if frame is not None and frame.f_code.co_name == "receive_bid" and frame.f_code.co_filename.endswith("app/main.py"):
                                frames.append(frame.f_locals)
                            coroutine = getattr(coroutine, "cr_await", None)
                    assert frames, "actual suspended handler frame is observable"
                    assert all(not {"arguments", "payload", "key", "bid_request", "body"}.intersection(frame) for frame in frames)
                request.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await request
                state = app.state.pipeline.snapshot()
                assert state["admission_undecided"] == state["preparation_count"] == 0
                assert state["admission_rejected"] == (0 if after_receipt else 1)
                assert state["outstanding"] == (1 if after_receipt else 0)
                assert state["client_failed"] == 0
                await client.post("/benchmark/conformance", headers=TOKEN, json={"action":"cleanup"})
                state = app.state.pipeline.snapshot()
                assert state["outstanding"] == 0
                assert state["client_succeeded"] == (1 if after_receipt else 0)
    asyncio.run(run())
