import asyncio
import json
import logging
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import urllib.request

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, telemetry_profile
from app.main import create_app
from app.runtime import BoundedSdkDiagnostics, cgroup_readback
from app.server import main


def test_profile_validation_and_zero_linger(monkeypatch):
    assert telemetry_profile(None) == "controlled"
    assert telemetry_profile(" OPERATIONAL ") == "operational"
    with pytest.raises(ValueError):
        telemetry_profile("unknown")
    monkeypatch.setenv("BENCHMARK_KAFKA_LINGER_MS", "0")
    assert Settings.from_env().kafka_linger_ms == 0


@pytest.mark.parametrize("profile,expected", [("controlled", False), ("operational", True)])
def test_actual_server_config_access_log_switch(monkeypatch, profile, expected):
    observed = []
    class Server:
        def __init__(self, config):
            observed.append(config)
        def run(self):
            pass
    monkeypatch.setenv("HTTP_SERVER_WORKERS", "1")
    monkeypatch.setenv("BENCHMARK_TELEMETRY_PROFILE", profile)
    monkeypatch.setattr("app.server.ReceiverServer", Server)
    main()
    assert observed[0].access_log is expected


def test_runtime_no_producer_in_http_only_and_actual_gc_process_fields():
    app = create_app(settings=Settings(delivery_mode="http-only"))
    with TestClient(app) as client:
        data = client.get("/benchmark/runtime").json()
        assert data["schema_version"] == 1 and data["scope"] == "worker_process"
        assert data["telemetry"]["profile"] == "controlled"
        assert not any(data["telemetry"][name] for name in ["access_log", "tracing", "trace_export", "body_size_metrics", "request_latency_histogram"])
        assert data["producer"]["instances"] == 0
        assert all(control["status"] == "not_applicable" for control in data["producer"]["controls"].values())
        assert data["runtime"]["pid"] > 0 and data["runtime"]["kind"] == "cpython"
        assert data["runtime"]["heap"]["max_bytes"] is None
        assert isinstance(data["runtime"]["heap"]["gc"]["enabled"], bool)
        assert data["pipeline"]["effective_bounds"]["max_records"] == 1024
        assert client.post("/bid-request", json={"id":"id", "site":{}, "device":{}}).status_code == 200
        assert app.state.pipeline.snapshot()["admission_candidates"] == 0


def test_real_producer_constructor_metadata_does_not_claim_sdk_readback(monkeypatch):
    observed = []
    class Producer:
        def __init__(self, **kwargs):
            observed.append(kwargs)
        async def start(self): pass
        async def stop(self): pass
    monkeypatch.setattr("app.kafka.AIOKafkaProducer", Producer)
    monkeypatch.setenv("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", "4")
    app = create_app(settings=Settings(delivery_mode="enqueue", kafka_acks="all", kafka_linger_ms=0))
    with TestClient(app) as client:
        data = client.get("/benchmark/runtime").json()
        assert data["producer"]["instances"] == len(observed) == 1
        controls = data["producer"]["controls"]
        for name, argument in [("acks","acks"), ("linger_ms","linger_ms"), ("batch_bytes","max_batch_size"),
                               ("request_timeout_ms","request_timeout_ms"), ("retry_backoff_ms","retry_backoff_ms")]:
            assert controls[name]["applied"] == observed[0][argument]
            assert controls[name]["status"] == "applied" and controls[name]["effective"] is None
        assert controls["acks"]["applied"] == -1 and controls["linger_ms"]["applied"] == 0
        assert controls["retries"]["status"] == controls["producer_pool_size"]["status"] == "unsupported"
        assert controls["producer_pool_size"]["requested"] == 4


def test_sdk_diagnostics_bounded_without_suppressing_lifecycle():
    gate = BoundedSdkDiagnostics()
    sdk = logging.LogRecord("aiokafka.sender", logging.WARNING, "", 0, "failure", (), None)
    assert sum(gate.filter(sdk) for _ in range(100)) == 10
    lifecycle = logging.LogRecord("app.pipeline", logging.INFO, "", 0, "benchmark_drain_end", (), None)
    assert all(gate.filter(lifecycle) for _ in range(100))


def test_linux_cgroup_v2_current_process_mount_mapping(monkeypatch):
    files = {"/proc/self/cgroup":"0::/bench\n", "/proc/self/mountinfo":"1 0 0:1 / /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
             "/sys/fs/cgroup/bench/memory.max":"1073741824\n", "/sys/fs/cgroup/bench/cpu.max":"200000 100000\n",
             "/sys/fs/cgroup/bench/cpuset.cpus.effective":"0-1\n"}
    monkeypatch.setattr("app.runtime.sys.platform", "linux")
    monkeypatch.setattr(Path, "read_text", lambda path, **_kwargs: files[str(path)])
    data = cgroup_readback()
    assert data["version"] == 2 and data["memory_max_bytes"] == 1073741824
    assert data["cpu_quota_us"] == 200000 and data["cpu_period_us"] == 100000
    assert data["cpuset_cpus_effective"] == "0-1"


def test_linux_cgroup_v1_and_host_unavailability(monkeypatch):
    files = {"/proc/self/cgroup":"1:memory:/bench\n2:cpu,cpuacct:/bench\n3:cpuset:/bench\n",
             "/proc/self/mountinfo":"1 0 0:1 / /cg/m rw - cgroup cgroup rw,memory\n2 0 0:2 / /cg/c rw - cgroup cgroup rw,cpu,cpuacct\n3 0 0:3 / /cg/s rw - cgroup cgroup rw,cpuset\n",
             "/cg/m/bench/memory.limit_in_bytes":"536870912", "/cg/c/bench/cpu.cfs_quota_us":"-1",
             "/cg/c/bench/cpu.cfs_period_us":"100000", "/cg/s/bench/cpuset.effective_cpus":"2,4"}
    monkeypatch.setattr("app.runtime.sys.platform", "linux")
    monkeypatch.setattr(Path, "read_text", lambda path, **_kwargs: files[str(path)])
    data = cgroup_readback()
    assert data["version"] == 1 and data["memory_max_bytes"] == 536870912
    assert data["cpu_quota_us"] is None and data["cpu_period_us"] == 100000
    monkeypatch.setattr("app.runtime.sys.platform", "darwin")
    data = cgroup_readback()
    assert data["version"] is None and data["memory_max_bytes"] is None


@pytest.mark.skipif(os.name != "posix", reason="receiver lifecycle uses POSIX SIGTERM")
@pytest.mark.parametrize("profile", ["controlled", "operational"])
def test_actual_running_server_metadata_and_access_log_profile(profile):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    env = {**os.environ, "PORT": str(port), "HTTP_SERVER_HOST": "127.0.0.1",
           "HTTP_SERVER_WORKERS": "1", "BENCHMARK_DELIVERY_MODE": "http-only",
           "BENCHMARK_TELEMETRY_PROFILE": profile, "PYTHONASYNCIODEBUG": "1"}
    process = subprocess.Popen([sys.executable, "-m", "app.server"],
        cwd=Path(__file__).parents[1], env=env, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True)
    try:
        deadline = time.monotonic() + 5
        while True:
            assert process.poll() is None, "receiver exited before runtime metadata became available"
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/benchmark/runtime", timeout=0.2) as response:
                    data = json.load(response)
                break
            except OSError:
                assert time.monotonic() < deadline, "runtime endpoint did not become ready"
                time.sleep(0.01)
        assert data["runtime"]["pid"] == process.pid
        assert data["runtime"]["http_parallelism"]["effective"] == 1
        assert data["runtime"]["http_parallelism"]["requested"] == 1
        assert data["telemetry"]["profile"] == profile
        assert data["telemetry"]["access_log"] is (profile == "operational")
        assert data["producer"]["instances"] == 0
        if profile == "controlled":
            assert data["telemetry"]["evidence"]["asyncio_debug"] is False
        process.send_signal(signal.SIGTERM)
        output, _ = process.communicate(timeout=5)
        assert process.returncode == 0, output
        access_logged = 'GET /benchmark/runtime HTTP/1.1" 200' in output
        assert access_logged is (profile == "operational"), output
        end = next(json.loads(line.split("benchmark_drain_end ", 1)[1])
                   for line in output.splitlines() if "benchmark_drain_end " in line)
        assert end["closing"] and end["teardown_confirmed"]
        assert end["drain_result"] == "settled"
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
