import json
import asyncio
import inspect
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

import pytest
import uvicorn

from app.config import Settings
from app.main import create_app
from app.pipeline import BoundedPipeline
from app.server import ReceiverServer


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_healthy(process, port):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        assert process.poll() is None, "receiver exited before becoming healthy"
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=0.1) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            time.sleep(0.01)
    raise AssertionError("receiver did not start within test deadline")


@pytest.mark.skipif(os.name != "posix", reason="Docker receiver uses POSIX SIGTERM")
@pytest.mark.parametrize("mode,expected_exit", [("http-only", 0), ("enqueue", 1)])
def test_actual_single_worker_sigterm_reports_failed_drain(mode, expected_exit):
    port = free_port()
    script = '''
import asyncio
import uvicorn
from app.config import Settings
from app.main import create_app
from app.server import ReceiverServer
class HeldPublisher:
    async def start(self): pass
    async def stop(self): pass
    async def publish(self, **record): await asyncio.Event().wait()
app = create_app(settings=Settings(delivery_mode=MODE, drain_timeout_ms=10), publisher=HeldPublisher())
ReceiverServer(uvicorn.Config(app, host="127.0.0.1", port=PORT, timeout_graceful_shutdown=0.01)).run()
'''.replace("MODE", repr(mode)).replace("PORT", str(port))
    process = subprocess.Popen([sys.executable, "-c", script], cwd=Path(__file__).parents[1], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        wait_healthy(process, port)
        request = urllib.request.Request(f"http://127.0.0.1:{port}/bid-request",
            data=b'{"id":"id","site":{},"device":{}}', headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=1) as response:
            assert response.status == 200
        process.send_signal(signal.SIGTERM)
        output, _ = process.communicate(timeout=5)
        assert process.returncode == expected_exit, output
        end = next(line.split("benchmark_drain_end ", 1)[1] for line in output.splitlines() if "benchmark_drain_end " in line)
        state = json.loads(end)
        assert state["teardown_confirmed"] is True
        assert state["outstanding"] == 0
        assert state["drain_result"] == ("settled" if expected_exit == 0 else "timed_out")
        assert state["abandoned_unknown"] == (0 if expected_exit == 0 else 1)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


@pytest.mark.skipif(os.name != "posix", reason="Docker receiver uses POSIX SIGTERM")
def test_two_worker_supervisor_uses_real_signal_hook_and_static_shares():
    port = free_port()
    env = {**os.environ, "PORT": str(port), "HTTP_SERVER_WORKERS": "2", "BENCHMARK_DELIVERY_MODE": "http-only"}
    process = subprocess.Popen([sys.executable, "-m", "app.server"], cwd=Path(__file__).parents[1], env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        wait_healthy(process, port)
        process.send_signal(signal.SIGTERM)
        output, _ = process.communicate(timeout=6)
        assert process.returncode == 0, output
        ends = [json.loads(line.split("benchmark_drain_end ", 1)[1]) for line in output.splitlines() if "benchmark_drain_end " in line]
        assert len(ends) == 2, output
        assert len({end["pid"] for end in ends}) == 2
        assert sum(end["effective_bounds"]["max_records"] for end in ends) == 1024
        assert sum(end["effective_bounds"]["max_preparations"] for end in ends) == 128
        assert all(end["closing"] and end["teardown_confirmed"] for end in ends)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()


@pytest.mark.skipif(os.name != "posix", reason="Docker receiver uses POSIX SIGTERM")
def test_sigterm_interrupting_commit_is_scheduled_outside_ledger_mutation():
    class HeldPublisher:
        async def start(self): pass
        async def stop(self): pass
        async def publish(self, **record): await asyncio.Event().wait()

    async def run():
        app = create_app(settings=Settings(delivery_mode="enqueue", drain_timeout_ms=1), publisher=HeldPublisher())
        pipeline = app.state.pipeline
        pipeline.ready = True
        config = uvicorn.Config(app)
        config.loaded_app = app
        server = ReceiverServer(config)
        candidate = pipeline.candidate()
        source, start = inspect.getsourcelines(BoundedPipeline.commit)
        interrupt_line = start + next(i for i, line in enumerate(source) if "del self.candidates[candidate.attempt]" in line)
        fired = []
        def trace(frame, event, _arg):
            if not fired and event == "line" and frame.f_code is BoundedPipeline.commit.__code__ and frame.f_lineno == interrupt_line:
                fired.append(True)
                os.kill(os.getpid(), signal.SIGTERM)
            return trace
        previous_trace = sys.gettrace()
        with server.capture_signals():
            sys.settrace(trace)
            try:
                receipt = pipeline.commit(candidate, topic="bids", key=b"id", value=b"value")
            finally:
                sys.settrace(previous_trace)
            assert fired == [True]
            assert not pipeline.closing  # signal queued closure, no nested mutation
            await asyncio.sleep(0)  # advance the event loop; the queued closure now runs
        state = pipeline.snapshot()
        assert state["closing"] and server.should_exit
        assert state["admitted"] == 1 and state["outstanding"] == 1
        assert state["admission_rejected"] == 0 and state["admission_undecided"] == 0
        assert receipt.attempt in pipeline.records
        end = await pipeline.shutdown()
        assert end["admitted"] == end["client_succeeded"] + end["client_failed"] + end["abandoned_unknown"] + end["outstanding"]
    asyncio.run(run())
