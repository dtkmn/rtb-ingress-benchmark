from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import Settings
from .kafka import (
    BidPublisher,
    KafkaBidPublisher,
    PublishBackpressureError,
    PublishUnavailableError,
)
from .models import BidRequest


logging.basicConfig(level=logging.INFO)
LOGGER = logging.getLogger(__name__)


def create_app(
    settings: Settings | None = None,
    publisher: BidPublisher | None = None,
    bootstrap_publisher: bool = True,
) -> FastAPI:
    effective_settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        runtime_publisher = publisher
        if runtime_publisher is None and bootstrap_publisher and effective_settings.uses_kafka():
            runtime_publisher = KafkaBidPublisher(effective_settings)

        app.state.settings = effective_settings
        app.state.publisher = runtime_publisher

        if runtime_publisher is not None:
            await runtime_publisher.start()
            LOGGER.info(
                "Initialized Python receiver publisher (delivery_mode=%s, topic=%s, acks=%s, retry_backoff_ms=%s)",
                effective_settings.delivery_mode,
                effective_settings.kafka_topic,
                effective_settings.kafka_acks,
                effective_settings.kafka_retry_backoff_ms,
            )
        else:
            LOGGER.info("HTTP-only benchmark mode enabled; skipping Kafka producer initialization")

        try:
            yield
        finally:
            if runtime_publisher is not None:
                await runtime_publisher.stop()

    app = FastAPI(title="python-receiver", lifespan=lifespan)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        _request: Request,
        _exc: RequestValidationError,
    ) -> JSONResponse:
        return JSONResponse(status_code=400, content={"status": "bad request"})

    @app.post("/bid-request")
    async def receive_bid(bid_request: BidRequest):
        settings = app.state.settings
        runtime_publisher = app.state.publisher

        if not bid_request.id or bid_request.device is None or (
            bid_request.site is None and bid_request.app is None
        ):
            return JSONResponse(status_code=400, content={"status": "bad request"})

        if bid_request.device.lmt == 1:
            return Response(status_code=204)

        if bid_request.device.ip and bid_request.device.ip.startswith("10.10."):
            return Response(status_code=204)

        if settings.is_http_only():
            return {"status": "accepted"}

        try:
            payload = bid_request.model_dump_json(exclude_none=True).encode()
        except Exception:
            return JSONResponse(status_code=500, content={"status": "serialization error"})

        if runtime_publisher is None:
            return JSONResponse(status_code=503, content={"status": "kafka unavailable"})

        try:
            await runtime_publisher.publish(
                topic=settings.kafka_topic,
                payload=payload,
                key=bid_request.id.encode(),
                confirm=settings.is_confirm(),
            )
        except PublishUnavailableError:
            return JSONResponse(status_code=503, content={"status": "kafka unavailable"})
        except PublishBackpressureError:
            return JSONResponse(status_code=503, content={"status": "kafka buffer full"})

        return {"status": "accepted"}

    @app.get("/health")
    async def health():
        return {"status": "healthy"}

    return app


app = create_app()
