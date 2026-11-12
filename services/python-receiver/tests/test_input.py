import asyncio
import base64
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.input import JsonNumber
from app.main import create_app
from app.models import BidRequest


CORPUS = json.loads((Path(__file__).parents[3] / "tests/conformance/corpus.json").read_text())
NORMAL = [case for case in CORPUS["cases"] if case["category"] != "fault"]


class RecordingPublisher:
    def __init__(self):
        self.started = False
        self.records = []

    async def start(self):
        self.started = True

    async def stop(self):
        pass

    async def publish(self, **record):
        self.records.append(record)


@pytest.mark.parametrize("mode", ["http-only", "confirm", "enqueue"])
@pytest.mark.parametrize("case", NORMAL, ids=lambda case: case["id"])
def test_shared_normal_corpus_and_record_intents(case, mode):
    publisher = RecordingPublisher()
    app = create_app(settings=Settings(delivery_mode=mode, kafka_topic="configured-topic"), publisher=publisher)
    encoded = case["request"]["body"]
    body = base64.b64decode(encoded["data"]) if encoded["encoding"] == "base64" else encoded["data"].encode("utf-8")
    headers = [(header["name"], header["value"]) for header in case["request"]["headers"]]
    expected = case["expectations"][mode]
    with TestClient(app) as client:
        response = client.request("POST", "/bid-request", content=body, headers=headers)
        assert response.status_code == expected["status"]
        if response.status_code == 200:
            assert response.json() == {"status": "accepted"}
        if response.status_code == 204:
            assert response.content == b""
        async def settled():
            for _ in range(100):
                if app.state.pipeline.snapshot()["outstanding"] == 0:
                    return
                await asyncio.sleep(0)
            raise AssertionError("record intent did not settle")
        client.portal.call(settled)
        state = client.get("/benchmark/state").json()
        for field, count in expected["accounting"].items():
            assert state[field] == count, (field, state)
        assert state["preparation_count"] == state["outstanding_bytes"] == 0
        assert len(publisher.records) == expected["accounting"]["admitted"]
        if "record" in expected:
            actual = publisher.records[0]
            assert actual["topic"] == expected["record"]["topic"]
            assert actual["key"] == expected["record"]["key"].encode("utf-8")
            assert json.loads(actual["payload"]) == expected["record"]["value"]
    assert publisher.started == (mode != "http-only")


@pytest.mark.parametrize("token,value", [("-0", 0), ("0e" + "9" * 6000, 0),
    ("1.000e+000", 1), ("1000e-3", 1), ("0.00100e3", 1), ("1e-0", 1)])
def test_exact_number_classifier_accepts_math_zero_and_one(token, value):
    assert JsonNumber(token).binary_value() == value


@pytest.mark.parametrize("token", ["1.000000000000000001", "0.99999999999999999999", "1e-9999", "1e" + "9" * 6000, "-1", "0.000000000001"])
def test_exact_number_classifier_never_rounds_into_domain(token):
    with pytest.raises(ValueError):
        JsonNumber(token).binary_value()


@pytest.mark.parametrize("mode,payload,status", [
    ("http-only", {"id": "id", "site": {}, "device": {}}, 200),
    ("enqueue", {"id": "id", "site": {}, "device": {"lmt": 1}}, 204),
])
def test_http_only_and_filters_do_not_serialize(monkeypatch, mode, payload, status):
    def fail(*_args, **_kwargs):
        raise AssertionError("record serializer must not run")
    monkeypatch.setattr(BidRequest, "model_dump_json", fail)
    publisher = RecordingPublisher()
    app = create_app(settings=Settings(delivery_mode=mode), publisher=publisher)
    with TestClient(app) as client:
        assert client.post("/bid-request", json=payload).status_code == status
        assert app.state.pipeline.snapshot()["admission_candidates"] == 0
    assert not publisher.records


def test_header_errors_precede_occupied_preparation_without_rejection_count():
    app = create_app(settings=Settings(delivery_mode="http-only", preparation_max_requests=1))
    with TestClient(app) as client:
        lease = client.portal.call(app.state.pipeline.prepare)
        try:
            for headers in [[("content-type", "text/plain")],
                            [("content-type", "application/json"), ("content-type", "application/json")],
                            [("content-type", "application/json"), ("content-encoding", "identity"), ("content-encoding", "identity")]]:
                assert client.post("/bid-request", content=b" " * 65537, headers=headers).status_code == 415
                assert app.state.pipeline.snapshot()["preparation_rejected"] == 0
                assert app.state.pipeline.snapshot()["preparation_count"] == 1
            assert client.post("/bid-request", content=b"{", headers={"content-type": "application/json"}).status_code == 503
            assert app.state.pipeline.snapshot()["preparation_rejected"] == 1
            assert app.state.pipeline.snapshot()["admission_candidates"] == 0
        finally:
            client.portal.call(lease.release)


@pytest.fixture
def actual_http_receiver():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    env = {**os.environ, "HTTP_SERVER_HOST": "127.0.0.1", "HTTP_SERVER_WORKERS": "1",
           "BENCHMARK_DELIVERY_MODE": "http-only", "PORT": str(port)}
    process = subprocess.Popen([sys.executable, "-m", "app.server"], cwd=Path(__file__).parents[1], env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + 5
        while True:
            assert process.poll() is None
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=.1):
                    break
            except OSError:
                assert time.monotonic() < deadline
                time.sleep(.01)
        yield port
    finally:
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)
        process.wait(timeout=5)
        assert process.returncode == 0


def raw_status(port, headers, body=b""):
    with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
        connection.sendall(b"POST /bid-request HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" + headers + b"\r\n" + body)
        response = connection.recv(4096)
        return int(response.split(b" ", 2)[1])


def test_actual_http_duplicate_headers_and_header_before_body(actual_http_receiver):
    body = b'{"id":"id","site":{},"device":{}}'
    length = f"Content-Length: {len(body)}\r\n".encode()
    assert raw_status(actual_http_receiver, b"Content-Type: application/json\r\nContent-Type: application/json\r\n" + length, body) == 415
    assert raw_status(actual_http_receiver, b"Content-Type: application/json\r\nContent-Encoding: identity\r\nContent-Encoding: identity\r\n" + length, body) == 415
    assert raw_status(actual_http_receiver, b"Content-Type: text/plain\r\nContent-Length: 65537\r\n") == 415


def test_actual_http_chunked_actual_byte_boundary(actual_http_receiver):
    minimal = b'{"id":"id","site":{},"device":{}}'
    for length, expected in [(65536, 200), (65537, 413)]:
        body = minimal + b" " * (length - len(minimal))
        chunks = [body[:32768], body[32768:]]
        encoded = b"".join(f"{len(chunk):x}\r\n".encode() + chunk + b"\r\n" for chunk in chunks) + b"0\r\n\r\n"
        assert raw_status(actual_http_receiver, b"Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n", encoded) == expected
