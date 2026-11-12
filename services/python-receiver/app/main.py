from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from .config import Settings
from .kafka import (
    BidPublisher,
    KafkaBidPublisher,
)
from .models import BidRequest
from .input import decode_json, valid_headers
from .pipeline import AdmissionRejected, BoundedPipeline
from .runtime import configure_telemetry, runtime_snapshot
from .conformance import ConformanceController
import json


logging.basicConfig(level=logging.INFO)
LOGGER = logging.getLogger(__name__)


class PreparationMiddleware:
    """Gate before FastAPI can read/parse the body, including HTTP-only."""
    def __init__(self, app, pipeline: BoundedPipeline):
        self.app = app
        self.pipeline = pipeline

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope.get("path") != "/bid-request" or scope.get("method") != "POST":
            return await self.app(scope, receive, send)
        async def counted_send(message):
            if message["type"] == "http.response.start":
                self.pipeline.http_statuses[str(message["status"])] += 1
            await send(message)
        if not valid_headers(Request(scope, receive=receive).headers):
            return await JSONResponse(status_code=415, content={"status": "unsupported media"})(scope, receive, counted_send)
        lease = self.pipeline.prepare()
        if lease is None:
            return await JSONResponse(status_code=503, content={"status": "intake unavailable"})(scope, receive, counted_send)
        scope.setdefault("state", {})["preparation_lease"] = lease
        try:
            await self.app(scope, receive, counted_send)
        finally:
            lease.release_from_handler()


def create_app(
    settings: Settings | None = None,
    publisher: BidPublisher | None = None,
    bootstrap_publisher: bool = True,
) -> FastAPI:
    effective_settings = settings or Settings.from_env()
    runtime_publisher = publisher if effective_settings.uses_kafka() else None
    pipeline = BoundedPipeline(effective_settings, runtime_publisher)
    conformance = ConformanceController.from_env(effective_settings, pipeline)
    if conformance is not None and effective_settings.uses_kafka():
        runtime_publisher = conformance

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        nonlocal runtime_publisher
        configure_telemetry(effective_settings.telemetry_profile)
        if effective_settings.is_confirm() and effective_settings.kafka_acks == 0:
            raise ValueError("confirm does not support Kafka acknowledgements=0")
        if runtime_publisher is None and bootstrap_publisher and effective_settings.uses_kafka():
            runtime_publisher = KafkaBidPublisher(effective_settings)

        app.state.settings = effective_settings
        app.state.publisher = runtime_publisher
        pipeline.transport = runtime_publisher

        if runtime_publisher is not None:
            await runtime_publisher.start()
            pipeline.ready = True
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
            await pipeline.shutdown()

    app = FastAPI(title="python-receiver", lifespan=lifespan)
    app.state.pipeline = pipeline
    app.state.conformance = conformance
    app.add_middleware(PreparationMiddleware, pipeline=pipeline)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(
        _request: Request,
        _exc: RequestValidationError,
    ) -> JSONResponse:
        return JSONResponse(status_code=400, content={"status": "bad request"})

    @app.post("/bid-request")
    async def receive_bid(request: Request):
        settings = app.state.settings
        body = bytearray()
        async for chunk in request.stream():
            if len(body) + len(chunk) > 65536:
                return JSONResponse(status_code=413, content={"status": "body too large"})
            body.extend(chunk)
        try:
            bid_request = BidRequest.model_validate(decode_json(body))
        except (ValidationError, ValueError, UnicodeError, RecursionError):
            return JSONResponse(status_code=400, content={"status": "bad request"})
        del body

        if not bid_request.id or bid_request.device is None or (
            bid_request.site is None and bid_request.app is None
        ):
            return JSONResponse(status_code=400, content={"status": "bad request"})

        if bid_request.device.lmt == 1:
            return Response(status_code=204)

        if bid_request.device.ip and bid_request.device.ip.startswith("10.10."):
            return Response(status_code=204)

        if settings.is_http_only():
            del bid_request
            request.state.preparation_lease.release()
            return {"status": "accepted"}

        candidate = pipeline.candidate()
        try:
            if conformance is not None:
                conformance.serialization_boundary()
            payload = bid_request.model_dump_json(exclude_none=True).encode()
            key = bid_request.id.encode()
        except Exception:
            pipeline.reject(candidate, "serialization")
            return JSONResponse(status_code=500, content={"status": "serialization error"})

        try:
            if conformance is not None:
                receipt = await conformance.admit(candidate, preparation_lease=request.state.preparation_lease,
                                                 topic=settings.kafka_topic, value=payload, key=key)
            else:
                receipt = pipeline.commit(candidate, topic=settings.kafka_topic, value=payload, key=key)
        except AdmissionRejected:
            return JSONResponse(status_code=503, content={"status": "kafka unavailable"})
        except Exception:
            pipeline.reject(candidate, "preparation")
            return JSONResponse(status_code=500, content={"status": "record preparation error"})
        del payload, key, bid_request
        request.state.preparation_lease.release()
        if settings.is_confirm() and not await pipeline.confirm(receipt):
            return JSONResponse(status_code=503, content={"status": "kafka buffer full"})

        return {"status": "accepted"}

    @app.get("/health")
    async def health():
        if pipeline.closing:
            return JSONResponse(status_code=503, content={"status": "draining"})
        return {"status": "healthy"}

    @app.get("/benchmark/state")
    async def benchmark_state():
        return pipeline.snapshot()

    @app.get("/benchmark/runtime")
    async def runtime():
        return runtime_snapshot(app)

    @app.api_route("/benchmark/conformance", methods=["GET", "POST"])
    async def fault_control(request: Request):
        tokens = request.headers.getlist("x-conformance-token")
        if conformance is None or len(tokens) != 1 or not conformance.authorized(tokens[0]):
            return Response(status_code=404)
        if request.method == "GET":
            return conformance.snapshot()
        raw = bytearray()
        async for chunk in request.stream():
            if len(raw) + len(chunk) > 8192:
                return Response(status_code=413)
            raw.extend(chunk)
        try:
            command = json.loads(raw)
            if not isinstance(command, dict):
                raise ValueError("command must be an object")
            return await conformance.command(command)
        except (ValueError, RuntimeError, TimeoutError) as error:
            return JSONResponse(status_code=409, content={"error": str(error)[:512]})

    return app


app = create_app()
