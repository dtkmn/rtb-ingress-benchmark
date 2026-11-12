"""Read-only runtime facts; management calls stay outside measured requests."""
from __future__ import annotations

import asyncio
import gc
import logging
import importlib.metadata
import os
from pathlib import Path
import platform
import sys
import time
import threading


class BoundedSdkDiagnostics(logging.Filter):
    """Allow ten SDK/asyncio warning/error diagnostics per minute per process."""
    def __init__(self):
        super().__init__()
        self.window = time.monotonic()
        self.count = 0
        self.lock = threading.Lock()

    def filter(self, record):
        if not record.name.startswith(("aiokafka", "asyncio")):
            return True
        with self.lock:
            now = time.monotonic()
            if now - self.window >= 60:
                self.window, self.count = now, 0
            if record.levelno < logging.WARNING or self.count >= 10:
                return False
            self.count += 1
            return True


_diagnostics = BoundedSdkDiagnostics()


def configure_telemetry(profile: str):
    controlled = profile == "controlled"
    for name in ("aiokafka", "asyncio"):
        logging.getLogger(name).setLevel(logging.WARNING if controlled else logging.NOTSET)
    for handler in logging.getLogger().handlers:
        if controlled and _diagnostics not in handler.filters:
            handler.addFilter(_diagnostics)
        elif not controlled and _diagnostics in handler.filters:
            handler.removeFilter(_diagnostics)
    if controlled:
        asyncio.get_running_loop().set_debug(False)


def _mount_path(group: str, root: str, mount: str) -> Path:
    # Kernel metadata only; no caller-controlled path reaches this function.
    if group == "/":
        return Path(mount)
    try:
        relative = Path(group).relative_to(root)
    except ValueError:
        relative = Path(group.lstrip("/"))
    return Path(mount) / relative


def cgroup_readback():
    result = {"version": None, "memory_max_bytes": None, "cpu_quota_us": None,
              "cpu_period_us": None, "cpuset_cpus_effective": None,
              "raw": {"memory.max": None, "cpu.max": None, "cpuset.cpus.effective": None},
              "evidence": {"scope": "current kernel cgroup files; ancestor memory/CPU constraints are not inferred"}}
    if sys.platform != "linux":
        result["evidence"]["unavailable"] = "cgroup readback is available only on Linux"
        return result
    try:
        groups = [line.split(":", 2) for line in Path("/proc/self/cgroup").read_text().splitlines()]
        mounts = [line.split(" - ", 1) for line in Path("/proc/self/mountinfo").read_text().splitlines()]
        paths = {}
        for before, after in mounts:
            fields, filesystem = before.split(), after.split()
            if filesystem[0] == "cgroup2":
                group = next((group for _, controllers, group in groups if controllers == ""), None)
                if group is not None:
                    base = _mount_path(group, fields[3], fields[4])
                    paths = {"memory.max": base / "memory.max", "cpu.max": base / "cpu.max",
                             "cpuset.cpus.effective": base / "cpuset.cpus.effective"}
                    result["version"] = 2
                    break
            elif filesystem[0] == "cgroup":
                controllers = set(filesystem[2].split(","))
                for controller, name, filename in [("memory", "memory.max", "memory.limit_in_bytes"),
                                                    ("cpu", "cpu.max", "cpu.cfs_quota_us"),
                                                    ("cpuset", "cpuset.cpus.effective", "cpuset.effective_cpus")]:
                    group = next((group for _, names, group in groups if controller in names.split(",")), None)
                    if controller in controllers and group is not None:
                        paths[name] = _mount_path(group, fields[3], fields[4]) / filename
                        result["version"] = 1
        for name, path in paths.items():
            try:
                value = path.read_text().strip()
                if result["version"] == 1 and name == "cpu.max":
                    value += " " + path.with_name("cpu.cfs_period_us").read_text().strip()
                result["raw"][name] = value
                result["evidence"][name] = str(path)
            except OSError:
                if result["version"] == 1 and name == "cpuset.cpus.effective":
                    try:
                        result["raw"][name] = path.with_name("cpuset.cpus").read_text().strip()
                        result["evidence"][name] = str(path.with_name("cpuset.cpus")) + " (configured v1 fallback)"
                    except OSError:
                        pass
        memory = result["raw"]["memory.max"]
        if memory is not None and memory != "max":
            limit = int(memory)
            # v1 represents no limit by a page-rounded signed-long sentinel.
            result["memory_max_bytes"] = limit if result["version"] == 2 or limit < 2 ** 60 else None
        cpu = result["raw"]["cpu.max"]
        if cpu is not None:
            quota, period = cpu.split()
            result["cpu_quota_us"] = None if quota in ("max", "-1") else int(quota)
            result["cpu_period_us"] = int(period)
        result["cpuset_cpus_effective"] = result["raw"]["cpuset.cpus.effective"]
    except (OSError, ValueError, IndexError) as error:
        result["evidence"]["unavailable"] = str(error)
    return result


def runtime_snapshot(app):
    settings = app.state.settings
    pipeline = app.state.pipeline
    publisher = app.state.publisher
    server = getattr(app.state, "server_runtime", {})
    controlled = settings.telemetry_profile == "controlled"
    effective_access = server.get("access_log")
    telemetry = {"profile": settings.telemetry_profile, "access_log": bool(effective_access),
                 "tracing": False, "trace_export": False, "body_size_metrics": False,
                 "request_latency_histogram": False,
                 "evidence": {"access_log": "actual Uvicorn Config.access_log" if effective_access is not None else "no ReceiverServer configuration (test/application embedding)",
                              "optional_work": "no tracing/export/body-size/histogram instrumentation is constructed in this receiver",
                              "asyncio_debug": asyncio.get_running_loop().get_debug(),
                              "sdk_diagnostics": "at most 10 warning/error messages per minute" if controlled else "existing logger behavior"}}
    from .kafka import KafkaBidPublisher
    real = isinstance(publisher, KafkaBidPublisher)
    controls = {}
    values = {"acks": -1 if settings.kafka_acks == "all" else settings.kafka_acks,
              "linger_ms": settings.kafka_linger_ms, "batch_bytes": settings.kafka_batch_bytes,
              "retries": settings.kafka_retries, "retry_backoff_ms": settings.kafka_retry_backoff_ms,
              "request_timeout_ms": settings.kafka_request_timeout_ms,
              "producer_pool_size": int(os.getenv("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", "1")) if os.getenv("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", "1").isdigit() else os.getenv("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE")}
    arguments = {"acks": "acks", "linger_ms": "linger_ms", "batch_bytes": "max_batch_size",
                 "retry_backoff_ms": "retry_backoff_ms", "request_timeout_ms": "request_timeout_ms"}
    for name, requested in values.items():
        status = "not_applicable" if not real else "unsupported" if name in ("retries", "producer_pool_size") else "applied"
        controls[name] = {"status": status, "requested": requested,
                          "applied": publisher.applied_settings.get(name) if status == "applied" else None,
                          "effective": None,
                          "evidence": ("no real producer constructed in this mode/application" if not real else
                                       "aiokafka has no fixed retry-count constructor control" if name == "retries" else
                                       "no producer-pool control; exactly one producer constructed per process" if name == "producer_pool_size" else
                                       f"AIOKafkaProducer constructor {arguments[name]}; public SDK effective getter unavailable")}
    state = pipeline.snapshot()
    return {"schema_version": 1, "lane": "python", "scope": "worker_process",
            "delivery_mode": settings.delivery_mode, "telemetry": telemetry,
            "runtime": {"pid": os.getpid(), "parent_pid": os.getppid(), "kind": sys.implementation.name,
                        "version": platform.python_version(), "http_parallelism": {
                            "control": "HTTP_SERVER_WORKERS", "requested": settings.workers,
                            "effective": 1 if server else None,
                            "evidence": "one ReceiverServer event loop in this observed process; container process census is external" if server else "no serving ReceiverServer observed",
                            "container_worker_count_requested": settings.workers},
                        "heap": {"max_bytes": None, "gc": {"enabled": gc.isenabled(), "thresholds": list(gc.get_threshold())},
                                 "evidence": "CPython GC readback; no application hard heap maximum; cgroup memory limit is separate"},
                        "event_loop": type(asyncio.get_running_loop()).__module__ + "." + type(asyncio.get_running_loop()).__name__,
                        "cgroup": cgroup_readback()},
            "producer": {"client": "aiokafka", "version": importlib.metadata.version("aiokafka"), "instances": 1 if real else 0, "controls": controls,
                         "validation_transport": publisher is not None and publisher is getattr(app.state, "conformance", None),
                         "validation_transport_evidence": "opt-in controlled transport proves owned pipeline only; no SDK instance or Kafka delivery is claimed" if getattr(app.state, "conformance", None) is not None else None},
            "pipeline": {"configured_bounds": state["configured_bounds"], "effective_bounds": state["effective_bounds"],
                         "http_outcome_timeout_ms": settings.http_outcome_timeout_ms, "drain_timeout_ms": settings.drain_timeout_ms}}
