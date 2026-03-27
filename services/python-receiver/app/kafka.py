from __future__ import annotations

import logging
from typing import Protocol

from aiokafka import AIOKafkaProducer
from aiokafka.errors import KafkaConnectionError, KafkaError, KafkaTimeoutError

from .config import Settings


LOGGER = logging.getLogger(__name__)


class PublishUnavailableError(RuntimeError):
    pass


class PublishBackpressureError(RuntimeError):
    pass


class BidPublisher(Protocol):
    async def start(self) -> None: ...

    async def stop(self) -> None: ...

    async def publish(
        self,
        *,
        topic: str,
        payload: bytes,
        key: bytes | None,
        confirm: bool,
    ) -> None: ...


class KafkaBidPublisher:
    def __init__(self, settings: Settings) -> None:
        self._producer = AIOKafkaProducer(
            bootstrap_servers=settings.kafka_bootstrap_servers,
            acks=settings.kafka_acks,
            client_id="python-receiver",
            linger_ms=settings.kafka_linger_ms,
            max_batch_size=settings.kafka_batch_bytes,
            request_timeout_ms=settings.kafka_request_timeout_ms,
            retry_backoff_ms=settings.kafka_retry_backoff_ms,
        )

    async def start(self) -> None:
        await self._producer.start()

    async def stop(self) -> None:
        await self._producer.stop()

    async def publish(
        self,
        *,
        topic: str,
        payload: bytes,
        key: bytes | None,
        confirm: bool,
    ) -> None:
        try:
            delivery_future = await self._producer.send(topic, payload, key=key)
        except (KafkaConnectionError, KafkaTimeoutError, KafkaError) as exc:
            LOGGER.warning("Kafka enqueue failed", exc_info=exc)
            raise PublishUnavailableError from exc

        if not confirm:
            return

        try:
            await delivery_future
        except (KafkaTimeoutError, KafkaError) as exc:
            LOGGER.warning("Kafka delivery confirmation failed", exc_info=exc)
            raise PublishBackpressureError from exc
