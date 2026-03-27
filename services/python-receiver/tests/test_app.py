from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import (
    DELIVERY_MODE_CONFIRM,
    DELIVERY_MODE_ENQUEUE,
    DELIVERY_MODE_HTTP_ONLY,
    Settings,
    normalize_delivery_mode,
    parse_kafka_acks,
    parse_non_negative_int,
    parse_positive_int,
)
from app.kafka import PublishBackpressureError, PublishUnavailableError
from app.main import create_app


class StubPublisher:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.calls: list[dict[str, object]] = []
        self.started = False
        self.stopped = False

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    async def publish(
        self,
        *,
        topic: str,
        payload: bytes,
        key: bytes | None,
        confirm: bool,
    ) -> None:
        self.calls.append(
            {
                "topic": topic,
                "payload": payload,
                "key": key,
                "confirm": confirm,
            }
        )
        if self.error is not None:
            raise self.error


def test_http_only_accepts_without_publisher() -> None:
    app = create_app(settings=Settings(delivery_mode=DELIVERY_MODE_HTTP_ONLY))
    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-1",
                "site": {"domain": "example.com"},
                "device": {"ip": "1.2.3.4", "lmt": 0},
            },
        )

    assert response.status_code == 200
    assert response.json() == {"status": "accepted"}


def test_confirm_mode_waits_for_delivery() -> None:
    publisher = StubPublisher()
    app = create_app(
        settings=Settings(delivery_mode=DELIVERY_MODE_CONFIRM),
        publisher=publisher,
    )

    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-2",
                "app": {"bundle": "com.example.app"},
                "device": {"lmt": 0},
            },
        )

    assert response.status_code == 200
    assert publisher.started is True
    assert publisher.stopped is True
    assert publisher.calls[0]["confirm"] is True


def test_enqueue_mode_accepts_after_local_publish() -> None:
    publisher = StubPublisher()
    app = create_app(
        settings=Settings(delivery_mode=DELIVERY_MODE_ENQUEUE),
        publisher=publisher,
    )

    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-3",
                "site": {"domain": "example.com"},
                "device": {"lmt": 0},
            },
        )

    assert response.status_code == 200
    assert publisher.calls[0]["confirm"] is False


def test_missing_required_fields_returns_bad_request() -> None:
    app = create_app(settings=Settings(delivery_mode=DELIVERY_MODE_HTTP_ONLY))
    with TestClient(app) as client:
        response = client.post("/bid-request", json={"device": {"lmt": 0}})

    assert response.status_code == 400
    assert response.json() == {"status": "bad request"}


def test_invalid_json_returns_bad_request() -> None:
    app = create_app(settings=Settings(delivery_mode=DELIVERY_MODE_HTTP_ONLY))
    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            content='{"id":"broken"',
            headers={"content-type": "application/json"},
        )

    assert response.status_code == 400
    assert response.json() == {"status": "bad request"}


def test_lmt_request_is_filtered() -> None:
    app = create_app(settings=Settings(delivery_mode=DELIVERY_MODE_HTTP_ONLY))
    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-4",
                "site": {"domain": "example.com"},
                "device": {"lmt": 1},
            },
        )

    assert response.status_code == 204


def test_blocked_ip_is_filtered() -> None:
    app = create_app(settings=Settings(delivery_mode=DELIVERY_MODE_HTTP_ONLY))
    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-5",
                "site": {"domain": "example.com"},
                "device": {"ip": "10.10.5.2", "lmt": 0},
            },
        )

    assert response.status_code == 204


def test_missing_publisher_returns_service_unavailable() -> None:
    app = create_app(
        settings=Settings(delivery_mode=DELIVERY_MODE_CONFIRM),
        bootstrap_publisher=False,
    )
    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-6",
                "site": {"domain": "example.com"},
                "device": {"lmt": 0},
            },
        )

    assert response.status_code == 503
    assert response.json() == {"status": "kafka unavailable"}


def test_confirm_failure_returns_buffer_full() -> None:
    app = create_app(
        settings=Settings(delivery_mode=DELIVERY_MODE_CONFIRM),
        publisher=StubPublisher(error=PublishBackpressureError()),
    )
    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-7",
                "site": {"domain": "example.com"},
                "device": {"lmt": 0},
            },
        )

    assert response.status_code == 503
    assert response.json() == {"status": "kafka buffer full"}


def test_enqueue_failure_returns_kafka_unavailable() -> None:
    app = create_app(
        settings=Settings(delivery_mode=DELIVERY_MODE_ENQUEUE),
        publisher=StubPublisher(error=PublishUnavailableError()),
    )
    with TestClient(app) as client:
        response = client.post(
            "/bid-request",
            json={
                "id": "bid-8",
                "site": {"domain": "example.com"},
                "device": {"lmt": 0},
            },
        )

    assert response.status_code == 503
    assert response.json() == {"status": "kafka unavailable"}


def test_delivery_mode_defaults_to_confirm() -> None:
    assert normalize_delivery_mode(None) == DELIVERY_MODE_CONFIRM
    assert normalize_delivery_mode("unexpected") == DELIVERY_MODE_CONFIRM


def test_parse_kafka_acks_supports_expected_values() -> None:
    assert parse_kafka_acks("0") == 0
    assert parse_kafka_acks("1") == 1
    assert parse_kafka_acks("all") == "all"


def test_parse_positive_int_supports_expected_values() -> None:
    assert parse_positive_int("131072", fallback=42, env_name="BENCHMARK_KAFKA_BATCH_BYTES") == 131072
    assert parse_positive_int("0", fallback=42, env_name="BENCHMARK_KAFKA_BATCH_BYTES") == 42
    assert parse_positive_int("weird", fallback=42, env_name="BENCHMARK_KAFKA_BATCH_BYTES") == 42


def test_parse_non_negative_int_supports_expected_values() -> None:
    assert parse_non_negative_int("5", fallback=42, env_name="BENCHMARK_KAFKA_RETRIES") == 5
    assert parse_non_negative_int("0", fallback=42, env_name="BENCHMARK_KAFKA_RETRIES") == 0
    assert parse_non_negative_int("-1", fallback=42, env_name="BENCHMARK_KAFKA_RETRIES") == 42
