from __future__ import annotations

import multiprocessing
import asyncio
import os
import signal
import threading
import time
from contextlib import contextmanager

import uvicorn
from .config import telemetry_profile


def worker_count(raw: str | None = None) -> int:
    candidate = raw if raw is not None else os.getenv("HTTP_SERVER_WORKERS")
    try:
        workers = int(candidate or "1")
    except ValueError:
        workers = 1
    return workers if workers > 0 else 1


class ReceiverServer(uvicorn.Server):
    async def startup(self, sockets=None):
        app = getattr(self.config, "loaded_app", None)
        while app is not None:
            state = getattr(app, "state", None)
            if state is not None and hasattr(state, "pipeline"):
                state.server_runtime = {"access_log": self.config.access_log, "host": self.config.host}
                break
            app = getattr(app, "app", None)
        await super().startup(sockets=sockets)

    @contextmanager
    def capture_signals(self):
        if threading.current_thread() is not threading.main_thread():
            yield
            return
        previous = {sig: signal.signal(sig, self.handle_exit)
                    for sig in (signal.SIGTERM, signal.SIGINT)}
        try:
            yield
        finally:
            for sig, handler in previous.items():
                signal.signal(sig, handler)
        # Uvicorn re-raises handled signals after shutdown. The receiver instead
        # reports the checked drain result through its explicit process status.

    def pipeline(self):
        app = getattr(self.config, "loaded_app", None)
        while app is not None:
            state = getattr(app, "state", None)
            if state is not None and hasattr(state, "pipeline"):
                return state.pipeline
            app = getattr(app, "app", None)
        return None

    def handle_exit(self, sig: int, frame) -> None:
        # Close intake before Uvicorn waits for HTTP tasks/lifespan teardown.
        pipeline = self.pipeline()
        if pipeline is not None:
            # A Python signal handler can interrupt synchronous commit bytecode.
            # Queue closure rather than mutate its ledger inside that stack.
            try:
                asyncio.get_running_loop().call_soon_threadsafe(pipeline.close_intake)
            except RuntimeError:
                pass  # no serving loop/request work exists before loop startup
        super().handle_exit(sig, frame)

    def run(self, sockets=None) -> None:
        super().run(sockets=sockets)
        pipeline = self.pipeline()
        if not self.started or pipeline is None:
            raise SystemExit(1)
        state = pipeline.snapshot()
        if state["drain_result"] != "settled" or not state["teardown_confirmed"]:
            raise SystemExit(1)


def run_worker(config, sockets) -> None:
    config.configure_logging()
    ReceiverServer(config).run(sockets=sockets)


def run_workers(config) -> None:
    # Pinned Uvicorn's supervisor creates its own Server, bypassing custom
    # signal hooks. Spawn the receiver server explicitly, with one shared
    # listening socket and conservative static container-budget shares.
    socket = config.bind_socket()
    context = multiprocessing.get_context("spawn")
    workers = [context.Process(target=run_worker, args=(config, [socket]))
               for _ in range(config.workers)]
    stopping = threading.Event()
    previous = {sig: signal.signal(sig, lambda *_args: stopping.set())
                for sig in (signal.SIGTERM, signal.SIGINT)}
    failed = False
    try:
        for worker in workers:
            worker.start()
        while not stopping.wait(0.1):
            if any(worker.exitcode is not None for worker in workers):
                failed = True  # unexpected worker exit is not silently restarted
                break
    finally:
        deadline = time.monotonic() + config.timeout_graceful_shutdown + 5
        for worker in workers:
            if worker.pid is not None and worker.is_alive():
                worker.terminate()
        for worker in workers:
            if worker.pid is not None:
                worker.join(max(0, deadline - time.monotonic()))
                if worker.is_alive():
                    worker.kill()
                    worker.join(1)
                    failed = True
                if worker.exitcode != 0:
                    failed = True
        socket.close()
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    if failed:
        raise SystemExit(1)


def main() -> None:
    host = os.getenv("HTTP_SERVER_HOST", "").strip() or "127.0.0.1"
    profile = telemetry_profile(os.getenv("BENCHMARK_TELEMETRY_PROFILE"))
    if profile == "controlled":
        os.environ.pop("PYTHONASYNCIODEBUG", None)
    config = uvicorn.Config(
        "app.main:app", host=host, port=int(os.getenv("PORT", "8080")),
        workers=worker_count(),
        timeout_graceful_shutdown=int(os.getenv("BENCHMARK_DRAIN_TIMEOUT_MS", "30000")) / 1000,
        access_log=profile == "operational",
    )
    if config.workers > 1:
        run_workers(config)
    else:
        ReceiverServer(config).run()


if __name__ == "__main__":
    main()
