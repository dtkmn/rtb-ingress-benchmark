from __future__ import annotations

import logging
import os
from dataclasses import dataclass


LOGGER = logging.getLogger(__name__)

DELIVERY_MODE_CONFIRM = "confirm"
DELIVERY_MODE_ENQUEUE = "enqueue"
DELIVERY_MODE_HTTP_ONLY = "http-only"


def telemetry_profile(raw: str | None = None) -> str:
    candidate = (raw or "controlled").strip().lower() or "controlled"
    if candidate not in {"controlled", "operational"}:
        raise ValueError("BENCHMARK_TELEMETRY_PROFILE must be controlled or operational")
    return candidate


def normalize_delivery_mode(raw: str | None) -> str:
    candidate = (raw or DELIVERY_MODE_CONFIRM).strip().lower()
    if candidate in {DELIVERY_MODE_CONFIRM, DELIVERY_MODE_ENQUEUE, DELIVERY_MODE_HTTP_ONLY}:
        return candidate

    LOGGER.warning(
        "Unknown BENCHMARK_DELIVERY_MODE=%r, defaulting to %s",
        raw,
        DELIVERY_MODE_CONFIRM,
    )
    return DELIVERY_MODE_CONFIRM


def parse_kafka_acks(raw: str | None) -> int | str:
    candidate = (raw or "1").strip().lower()
    if candidate in {"", "1", "leader"}:
        return 1
    if candidate in {"0", "none"}:
        return 0
    if candidate in {"-1", "all"}:
        return "all"

    LOGGER.warning(
        "Unknown BENCHMARK_KAFKA_ACKS=%r, defaulting to leader acknowledgements",
        raw,
    )
    return 1


def parse_positive_int(raw: str | None, *, fallback: int, env_name: str) -> int:
    candidate = (raw or "").strip()
    if candidate == "":
        return fallback

    try:
        value = int(candidate)
    except ValueError:
        LOGGER.warning("Ignoring invalid %s=%r; defaulting to %d", env_name, raw, fallback)
        return fallback

    if value <= 0:
        LOGGER.warning("Ignoring invalid %s=%r; defaulting to %d", env_name, raw, fallback)
        return fallback

    return value


def parse_non_negative_int(raw: str | None, *, fallback: int, env_name: str) -> int:
    candidate = (raw or "").strip()
    if candidate == "":
        return fallback

    try:
        value = int(candidate)
    except ValueError:
        LOGGER.warning("Ignoring invalid %s=%r; defaulting to %d", env_name, raw, fallback)
        return fallback

    if value < 0:
        LOGGER.warning("Ignoring invalid %s=%r; defaulting to %d", env_name, raw, fallback)
        return fallback

    return value


@dataclass(frozen=True)
class Settings:
    telemetry_profile: str = "controlled"
    delivery_mode: str = DELIVERY_MODE_CONFIRM
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_topic: str = "bids"
    kafka_acks: int | str = 1
    kafka_linger_ms: int = 10
    kafka_batch_bytes: int = 131072
    kafka_request_timeout_ms: int = 5000
    kafka_retries: int = 5
    kafka_retry_backoff_ms: int = 100
    workers: int = 1
    pipeline_max_records: int = 1024
    pipeline_max_bytes: int = 16 * 1024 * 1024
    preparation_max_requests: int = 128
    http_outcome_timeout_ms: int = 30000
    drain_timeout_ms: int = 30000

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            telemetry_profile=telemetry_profile(os.getenv("BENCHMARK_TELEMETRY_PROFILE")),
            **{field: parse_positive_int(os.getenv(name), fallback=default, env_name=name)
               for field, name, default in [
                   ("workers", "HTTP_SERVER_WORKERS", 1),
                   ("pipeline_max_records", "BENCHMARK_PIPELINE_MAX_RECORDS", 1024),
                   ("pipeline_max_bytes", "BENCHMARK_PIPELINE_MAX_BYTES", 16777216),
                   ("preparation_max_requests", "BENCHMARK_PREPARATION_MAX_REQUESTS", 128),
                   ("http_outcome_timeout_ms", "BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS", 30000),
                   ("drain_timeout_ms", "BENCHMARK_DRAIN_TIMEOUT_MS", 30000),
               ]},
            delivery_mode=normalize_delivery_mode(os.getenv("BENCHMARK_DELIVERY_MODE")),
            kafka_bootstrap_servers=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            kafka_topic=os.getenv("BENCHMARK_KAFKA_TOPIC", "bids"),
            kafka_acks=parse_kafka_acks(os.getenv("BENCHMARK_KAFKA_ACKS")),
            kafka_linger_ms=parse_non_negative_int(
                os.getenv("BENCHMARK_KAFKA_LINGER_MS"),
                fallback=10,
                env_name="BENCHMARK_KAFKA_LINGER_MS",
            ),
            kafka_batch_bytes=parse_positive_int(
                os.getenv("BENCHMARK_KAFKA_BATCH_BYTES"),
                fallback=131072,
                env_name="BENCHMARK_KAFKA_BATCH_BYTES",
            ),
            kafka_request_timeout_ms=parse_positive_int(
                os.getenv("BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS"),
                fallback=5000,
                env_name="BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS",
            ),
            kafka_retries=parse_non_negative_int(
                os.getenv("BENCHMARK_KAFKA_RETRIES"),
                fallback=5,
                env_name="BENCHMARK_KAFKA_RETRIES",
            ),
            kafka_retry_backoff_ms=parse_non_negative_int(
                os.getenv("BENCHMARK_KAFKA_RETRY_BACKOFF_MS"),
                fallback=100,
                env_name="BENCHMARK_KAFKA_RETRY_BACKOFF_MS",
            ),
        )

    def uses_kafka(self) -> bool:
        return self.delivery_mode != DELIVERY_MODE_HTTP_ONLY

    def is_confirm(self) -> bool:
        return self.delivery_mode == DELIVERY_MODE_CONFIRM

    def is_http_only(self) -> bool:
        return self.delivery_mode == DELIVERY_MODE_HTTP_ONLY
