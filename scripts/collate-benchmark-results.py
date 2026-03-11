#!/usr/bin/env python3
from __future__ import annotations

import csv
from datetime import datetime
import json
import re
import statistics
import sys
from collections import defaultdict
from functools import lru_cache
from pathlib import Path


RUN_FILE_RE = re.compile(r"^(?P<service>.+)-run-(?P<run>\d+)-summary\.json$")
ANSI_ESCAPE_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
DIR_TIMESTAMP_RE = re.compile(r"^(?P<stamp>\d{8}-\d{6})$")
MODE_COMPARISON_META_KEYS = (
    "git_sha",
    "uname",
    "services",
    "vus",
    "rate",
    "duration",
    "warmup_duration",
    "lmt_percent",
    "blocked_ip_percent",
    "http_server_workers",
    "default_receiver_parallelism",
    "go_max_procs",
    "quarkus_http_io_threads",
    "spring_tomcat_threads_max",
    "spring_tomcat_threads_min_spare",
    "kafka_linger_ms",
    "kafka_batch_bytes",
    "kafka_request_timeout_ms",
    "receiver_cpus",
    "receiver_memory",
    "receiver_cpuset",
    "kafka_cpus",
    "kafka_memory",
    "kafka_cpuset",
)


def load_meta(meta_path: Path) -> dict[str, str]:
    meta: dict[str, str] = {}
    if not meta_path.exists():
        return meta

    for line in meta_path.read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            meta[key.strip()] = value.strip()
    return meta


def metric_value(metrics: dict, name: str, key: str, default: float = 0.0) -> float:
    metric = metrics.get(name, {})
    values = metric.get("values", metric)
    value = values.get(key, default)
    if value is None:
        return default
    return float(value)


def parse_percent(raw: str) -> float:
    return float(raw.strip().rstrip("%") or 0.0)


def parse_bytes(raw: str) -> float:
    units = {
        "b": 1,
        "kb": 1000,
        "mb": 1000**2,
        "gb": 1000**3,
        "tb": 1000**4,
        "kib": 1024,
        "mib": 1024**2,
        "gib": 1024**3,
        "tib": 1024**4,
    }

    raw = raw.strip()
    match = re.match(r"^(?P<value>[0-9]+(?:\.[0-9]+)?)\s*(?P<unit>[A-Za-z]+)?$", raw)
    if not match:
        raise ValueError(f"Cannot parse byte quantity: {raw!r}")

    value = float(match.group("value"))
    unit = (match.group("unit") or "b").lower()
    return value * units[unit]


def parse_mem_usage(raw: str) -> tuple[float, float]:
    usage, limit = [part.strip() for part in raw.split("/", 1)]
    return parse_bytes(usage), parse_bytes(limit)


def safe_ratio(numerator: float, denominator: float) -> float | None:
    if denominator <= 0:
        return None
    return numerator / denominator


def parse_timestamp(raw: str | None, fallback_dir_name: str = "") -> datetime | None:
    if raw:
        try:
            return datetime.fromisoformat(raw)
        except ValueError:
            pass

    match = DIR_TIMESTAMP_RE.match(fallback_dir_name)
    if not match:
        return None

    try:
        return datetime.strptime(match.group("stamp"), "%Y%m%d-%H%M%S")
    except ValueError:
        return None


def cpuset_cpu_count(raw: str) -> int:
    total = 0
    for chunk in raw.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        if "-" in chunk:
            start, end = chunk.split("-", 1)
            total += int(end) - int(start) + 1
        else:
            total += 1
    return total


def cpu_limit_cores(host_config: dict) -> float | None:
    limits: list[float] = []

    nano_cpus = int(host_config.get("NanoCpus", 0) or 0)
    if nano_cpus > 0:
        limits.append(nano_cpus / 1_000_000_000)

    cpu_quota = int(host_config.get("CpuQuota", 0) or 0)
    cpu_period = int(host_config.get("CpuPeriod", 0) or 0)
    if cpu_quota > 0 and cpu_period > 0:
        limits.append(cpu_quota / cpu_period)

    cpuset = (host_config.get("CpusetCpus") or "").strip()
    if cpuset:
        cpuset_count = cpuset_cpu_count(cpuset)
        if cpuset_count > 0:
            limits.append(float(cpuset_count))

    if not limits:
        return None

    return min(limits)


@lru_cache(maxsize=None)
def inspect_limits(inspect_path: Path) -> dict[str, float]:
    if not inspect_path.exists():
        return {}

    payload = json.loads(inspect_path.read_text())
    if not payload:
        return {}

    host_config = payload[0].get("HostConfig", {})
    limits: dict[str, float] = {}

    cpu_limit = cpu_limit_cores(host_config)
    if cpu_limit is not None:
        limits["cpu_limit_cores"] = cpu_limit

    mem_limit = float(host_config.get("Memory", 0) or 0)
    if mem_limit > 0:
        limits["mem_limit_bytes"] = mem_limit

    return limits


def summarize_stats(stats_path: Path) -> dict[str, float]:
    if not stats_path.exists():
        return {}

    cpu_values: list[float] = []
    mem_values: list[float] = []
    mem_limits: list[float] = []
    pids_values: list[int] = []

    cleaned = ANSI_ESCAPE_RE.sub("", stats_path.read_text())
    decoder = json.JSONDecoder()
    index = 0

    while index < len(cleaned):
        next_object = cleaned.find("{", index)
        if next_object == -1:
            break
        try:
            record, index = decoder.raw_decode(cleaned, next_object)
        except json.JSONDecodeError:
            index = next_object + 1
            continue

        cpu_values.append(parse_percent(record["CPUPerc"]))
        mem_usage, mem_limit = parse_mem_usage(record["MemUsage"])
        mem_values.append(mem_usage)
        mem_limits.append(mem_limit)
        pids_values.append(int(record["PIDs"]))

    if not cpu_values:
        return {}

    return {
        "cpu_avg_pct": statistics.fmean(cpu_values),
        "cpu_max_pct": max(cpu_values),
        "mem_avg_bytes": statistics.fmean(mem_values),
        "mem_max_bytes": max(mem_values),
        "mem_limit_bytes": max(mem_limits),
        "pids_max": max(pids_values),
    }


def add_derived_metrics(row: dict[str, float | int | str]) -> None:
    reqs = float(row.get("http_reqs_rate", 0.0))

    receiver_cpu_avg_pct = float(row.get("receiver_cpu_avg_pct", 0.0))
    receiver_cpu_max_pct = float(row.get("receiver_cpu_max_pct", 0.0))
    kafka_cpu_avg_pct = float(row.get("kafka_cpu_avg_pct", 0.0))
    kafka_cpu_max_pct = float(row.get("kafka_cpu_max_pct", 0.0))

    receiver_cpu_avg_cores = receiver_cpu_avg_pct / 100 if receiver_cpu_avg_pct else 0.0
    receiver_cpu_max_cores = receiver_cpu_max_pct / 100 if receiver_cpu_max_pct else 0.0
    kafka_cpu_avg_cores = kafka_cpu_avg_pct / 100 if kafka_cpu_avg_pct else 0.0
    kafka_cpu_max_cores = kafka_cpu_max_pct / 100 if kafka_cpu_max_pct else 0.0

    if receiver_cpu_avg_cores:
        row["receiver_cpu_avg_cores"] = receiver_cpu_avg_cores
    if receiver_cpu_max_cores:
        row["receiver_cpu_max_cores"] = receiver_cpu_max_cores
    if kafka_cpu_avg_cores:
        row["kafka_cpu_avg_cores"] = kafka_cpu_avg_cores
    if kafka_cpu_max_cores:
        row["kafka_cpu_max_cores"] = kafka_cpu_max_cores

    stack_cpu_avg_cores = receiver_cpu_avg_cores + kafka_cpu_avg_cores
    stack_mem_avg_bytes = float(row.get("receiver_mem_avg_bytes", 0.0)) + float(
        row.get("kafka_mem_avg_bytes", 0.0)
    )

    if stack_cpu_avg_cores:
        row["stack_cpu_avg_cores"] = stack_cpu_avg_cores
    if stack_mem_avg_bytes:
        row["stack_mem_avg_bytes"] = stack_mem_avg_bytes

    for limit_key, target_key in (
        ("receiver_cpu_limit_cores", "http_reqs_per_receiver_cpu_limit"),
        ("kafka_cpu_limit_cores", "http_reqs_per_kafka_cpu_limit"),
    ):
        ratio = safe_ratio(reqs, float(row.get(limit_key, 0.0)))
        if ratio is not None:
            row[target_key] = ratio

    for limit_key, target_key in (
        ("receiver_mem_limit_bytes", "http_reqs_per_receiver_mem_limit_gib"),
        ("kafka_mem_limit_bytes", "http_reqs_per_kafka_mem_limit_gib"),
    ):
        gib = float(row.get(limit_key, 0.0)) / (1024**3)
        ratio = safe_ratio(reqs, gib)
        if ratio is not None:
            row[target_key] = ratio

    receiver_cpu_efficiency = safe_ratio(reqs, receiver_cpu_avg_cores)
    if receiver_cpu_efficiency is not None:
        row["http_reqs_per_receiver_cpu_avg_core"] = receiver_cpu_efficiency

    stack_cpu_efficiency = safe_ratio(reqs, stack_cpu_avg_cores)
    if stack_cpu_efficiency is not None:
        row["http_reqs_per_stack_cpu_avg_core"] = stack_cpu_efficiency

    receiver_mem_gib = float(row.get("receiver_mem_avg_bytes", 0.0)) / (1024**3)
    receiver_mem_efficiency = safe_ratio(reqs, receiver_mem_gib)
    if receiver_mem_efficiency is not None:
        row["http_reqs_per_receiver_mem_avg_gib"] = receiver_mem_efficiency

    stack_mem_gib = stack_mem_avg_bytes / (1024**3)
    stack_mem_efficiency = safe_ratio(reqs, stack_mem_gib)
    if stack_mem_efficiency is not None:
        row["http_reqs_per_stack_mem_avg_gib"] = stack_mem_efficiency


def service_run_rows(results_dir: Path) -> list[dict[str, float | int | str]]:
    rows: list[dict[str, float | int | str]] = []
    kafka_limits = inspect_limits(results_dir / "kafka-container-inspect.json")

    for summary_file in sorted(results_dir.glob("*-run-*-summary.json")):
        match = RUN_FILE_RE.match(summary_file.name)
        if not match:
            continue

        service = match.group("service")
        run = int(match.group("run"))
        metrics = json.loads(summary_file.read_text())["metrics"]

        row: dict[str, float | int | str] = {
            "service": service,
            "run": run,
            "http_reqs_count": metric_value(metrics, "http_reqs", "count"),
            "http_reqs_rate": metric_value(metrics, "http_reqs", "rate"),
            "http_req_failed_rate": metric_value(metrics, "http_req_failed", "rate"),
            "checks_rate": metric_value(metrics, "checks", "value"),
            "valid_responses_rate": metric_value(metrics, "valid_responses", "value"),
            "accepted_responses": metric_value(metrics, "accepted_responses", "count"),
            "filtered_responses": metric_value(metrics, "filtered_responses", "count"),
            "http_req_duration_avg_ms": metric_value(metrics, "http_req_duration", "avg"),
            "http_req_duration_p90_ms": metric_value(metrics, "http_req_duration", "p(90)"),
            "http_req_duration_p95_ms": metric_value(metrics, "http_req_duration", "p(95)"),
            "http_req_duration_max_ms": metric_value(metrics, "http_req_duration", "max"),
        }

        receiver_limits = inspect_limits(results_dir / f"{service}-container-inspect.json")
        receiver_stats = summarize_stats(results_dir / f"{service}-run-{run:02d}-receiver-stats.ndjson")
        kafka_stats = summarize_stats(results_dir / f"{service}-run-{run:02d}-kafka-stats.ndjson")

        for key, value in receiver_limits.items():
            row[f"receiver_{key}"] = value
        for key, value in kafka_limits.items():
            row[f"kafka_{key}"] = value
        for key, value in receiver_stats.items():
            row[f"receiver_{key}"] = value
        for key, value in kafka_stats.items():
            row[f"kafka_{key}"] = value

        add_derived_metrics(row)
        rows.append(row)

    return rows


def aggregate_rows(rows: list[dict[str, float | int | str]]) -> list[dict[str, float | int | str]]:
    grouped: dict[str, list[dict[str, float | int | str]]] = defaultdict(list)
    for row in rows:
        grouped[str(row["service"])].append(row)

    aggregates: list[dict[str, float | int | str]] = []
    for service, service_rows in sorted(grouped.items()):
        aggregate: dict[str, float | int | str] = {
            "service": service,
            "runs": len(service_rows),
        }

        numeric_keys = sorted(
            {
                key
                for row in service_rows
                for key, value in row.items()
                if key not in {"service", "run"} and isinstance(value, (int, float))
            }
        )

        for key in numeric_keys:
            values = [float(row[key]) for row in service_rows if key in row]
            aggregate[f"{key}_avg"] = statistics.fmean(values)
            aggregate[f"{key}_max"] = max(values)
            aggregate[f"{key}_min"] = min(values)

        aggregates.append(aggregate)

    return aggregates


def load_summary_rows(results_dir: Path) -> list[dict[str, float | int | str]]:
    summary_path = results_dir / "summary.json"
    if summary_path.exists():
        payload = json.loads(summary_path.read_text())
        summary_rows = payload.get("summary")
        if isinstance(summary_rows, list):
            return summary_rows

    return aggregate_rows(service_run_rows(results_dir))


def numeric_value(row: dict[str, float | int | str], key: str) -> float:
    value = row.get(key)
    if value in (None, ""):
        return 0.0
    return float(value)


def mode_priority(current_mode: str, candidate_mode: str) -> int | None:
    if current_mode == candidate_mode:
        return None

    if current_mode == "http-only":
        if candidate_mode == "confirm":
            return 0
        if candidate_mode != "http-only":
            return 1
        return None

    if candidate_mode == "http-only":
        return 0

    return None


def meta_matches_for_mode_comparison(current_meta: dict[str, str], candidate_meta: dict[str, str]) -> bool:
    for key in MODE_COMPARISON_META_KEYS:
        if current_meta.get(key, "") != candidate_meta.get(key, ""):
            return False
    return True


def build_mode_comparison(
    results_dir: Path, meta: dict[str, str], rows: list[dict[str, float | int | str]]
) -> dict[str, object] | None:
    current_mode = meta.get("delivery_mode", "")
    current_priority_mode = current_mode or "unknown"
    current_timestamp = parse_timestamp(meta.get("timestamp"), results_dir.name)
    current_services = [
        str(row["service"])
        for row in sorted(rows, key=lambda row: numeric_value(row, "http_reqs_rate_avg"), reverse=True)
    ]

    best_match: tuple[tuple[int, float, str], Path, dict[str, str]] | None = None
    for candidate_dir in results_dir.parent.iterdir():
        if candidate_dir == results_dir or not candidate_dir.is_dir():
            continue

        candidate_meta = load_meta(candidate_dir / "run-meta.txt")
        if not candidate_meta:
            continue

        priority = mode_priority(current_priority_mode, candidate_meta.get("delivery_mode", ""))
        if priority is None:
            continue

        if not meta_matches_for_mode_comparison(meta, candidate_meta):
            continue

        candidate_timestamp = parse_timestamp(candidate_meta.get("timestamp"), candidate_dir.name)
        timestamp_delta = float("inf")
        if current_timestamp is not None and candidate_timestamp is not None:
            timestamp_delta = abs((candidate_timestamp - current_timestamp).total_seconds())

        candidate_key = (priority, timestamp_delta, candidate_dir.name)
        if best_match is None or candidate_key < best_match[0]:
            best_match = (candidate_key, candidate_dir, candidate_meta)

    if best_match is None:
        return None

    _, matched_dir, matched_meta = best_match
    matched_rows = load_summary_rows(matched_dir)
    current_by_service = {str(row["service"]): row for row in rows}
    matched_by_service = {str(row["service"]): row for row in matched_rows}

    if current_mode == "http-only":
        http_only_dir = results_dir
        http_only_rows = current_by_service
        kafka_dir = matched_dir
        kafka_meta = matched_meta
        kafka_rows = matched_by_service
    else:
        http_only_dir = matched_dir
        http_only_rows = matched_by_service
        kafka_dir = results_dir
        kafka_meta = meta
        kafka_rows = current_by_service

    comparison_rows: list[dict[str, float | str]] = []
    for service in current_services:
        if service not in http_only_rows or service not in kafka_rows:
            continue

        http_only_row = http_only_rows[service]
        kafka_row = kafka_rows[service]
        http_only_avg_ms = numeric_value(http_only_row, "http_req_duration_avg_ms_avg")
        kafka_avg_ms = numeric_value(kafka_row, "http_req_duration_avg_ms_avg")
        http_only_reqs = numeric_value(http_only_row, "http_reqs_rate_avg")
        kafka_reqs = numeric_value(kafka_row, "http_reqs_rate_avg")

        comparison_rows.append(
            {
                "service": service,
                "http_only_req_s_avg": http_only_reqs,
                "http_only_avg_ms": http_only_avg_ms,
                "kafka_delivery_mode": kafka_meta.get("delivery_mode", "unknown"),
                "kafka_req_s_avg": kafka_reqs,
                "kafka_avg_ms": kafka_avg_ms,
                "estimated_added_kafka_latency_ms": kafka_avg_ms - http_only_avg_ms,
                "latency_multiplier_vs_http_only": safe_ratio(kafka_avg_ms, http_only_avg_ms),
                "throughput_ratio_http_only_vs_kafka": safe_ratio(http_only_reqs, kafka_reqs),
            }
        )

    if not comparison_rows:
        return None

    return {
        "matched_results_dir": str(matched_dir),
        "matched_results_name": matched_dir.name,
        "matched_delivery_mode": matched_meta.get("delivery_mode", "unknown"),
        "http_only_results_dir": str(http_only_dir),
        "http_only_results_name": http_only_dir.name,
        "kafka_results_dir": str(kafka_dir),
        "kafka_results_name": kafka_dir.name,
        "kafka_delivery_mode": kafka_meta.get("delivery_mode", "unknown"),
        "match_keys": list(MODE_COMPARISON_META_KEYS),
        "rows": comparison_rows,
    }


def write_csv(path: Path, rows: list[dict[str, float | int | str]]) -> None:
    if not rows:
        return

    fieldnames = sorted({key for row in rows for key in row.keys()})
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def format_float(value: float | int | None, digits: int = 2) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.{digits}f}"


def format_bytes_in_mib(value: float | int | None) -> str:
    if value is None:
        return "n/a"
    return f"{float(value) / (1024**2):.2f}"


def kafka_enabled_label(meta: dict[str, str]) -> str:
    if "kafka_enabled" in meta:
        return meta["kafka_enabled"]
    return "false" if meta.get("delivery_mode") == "http-only" else "true"


def write_summary_markdown(
    path: Path,
    meta: dict[str, str],
    rows: list[dict[str, float | int | str]],
    mode_comparison: dict[str, object] | None = None,
) -> None:
    if not rows:
        return

    sorted_rows = sorted(rows, key=lambda row: float(row.get("http_reqs_rate_avg", 0.0)), reverse=True)
    lines = [
        "# Benchmark Summary",
        "",
        f"- delivery mode: {meta.get('delivery_mode', 'unknown')}",
        f"- kafka enabled: {kafka_enabled_label(meta)}",
        f"- kafka acks: {meta.get('kafka_acks', 'unknown')}",
        f"- services: {meta.get('services', 'unknown')}",
        f"- workload: {meta.get('vus', 'unknown')} VUs for {meta.get('duration', 'unknown')} (warmup {meta.get('warmup_duration', 'unknown')})",
        f"- receiver budget: {meta.get('receiver_cpus', 'unknown')} CPU / {meta.get('receiver_memory', 'unknown')}",
        f"- kafka budget: {meta.get('kafka_cpus', 'unknown')} CPU / {meta.get('kafka_memory', 'unknown')}",
        "",
        "## Throughput And Normalized Efficiency",
        "",
        "| service | runs | req/s avg | p95 avg (ms) | req/s / receiver CPU limit | req/s / receiver GiB limit | req/s / measured stack avg core | req/s / measured stack avg GiB |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]

    for row in sorted_rows:
        lines.append(
            "| {service} | {runs} | {reqs} | {p95} | {receiver_cpu_limit} | {receiver_mem_limit} | {stack_cpu_avg} | {stack_mem_avg} |".format(
                service=row["service"],
                runs=int(row["runs"]),
                reqs=format_float(row.get("http_reqs_rate_avg")),
                p95=format_float(row.get("http_req_duration_p95_ms_avg")),
                receiver_cpu_limit=format_float(row.get("http_reqs_per_receiver_cpu_limit_avg")),
                receiver_mem_limit=format_float(row.get("http_reqs_per_receiver_mem_limit_gib_avg")),
                stack_cpu_avg=format_float(row.get("http_reqs_per_stack_cpu_avg_core_avg")),
                stack_mem_avg=format_float(row.get("http_reqs_per_stack_mem_avg_gib_avg")),
            )
        )

    lines.extend(
        [
            "",
            "## Resource Footprint",
            "",
            "| service | receiver cpu avg % | receiver cpu max % | receiver mem avg MiB | receiver mem max MiB | kafka cpu max % | kafka mem max MiB |",
            "|---|---:|---:|---:|---:|---:|---:|",
        ]
    )

    for row in sorted_rows:
        lines.append(
            "| {service} | {receiver_cpu_avg} | {receiver_cpu_max} | {receiver_mem_avg} | {receiver_mem_max} | {kafka_cpu_max} | {kafka_mem_max} |".format(
                service=row["service"],
                receiver_cpu_avg=format_float(row.get("receiver_cpu_avg_pct_avg")),
                receiver_cpu_max=format_float(row.get("receiver_cpu_max_pct_max")),
                receiver_mem_avg=format_bytes_in_mib(row.get("receiver_mem_avg_bytes_avg")),
                receiver_mem_max=format_bytes_in_mib(row.get("receiver_mem_max_bytes_max")),
                kafka_cpu_max=format_float(row.get("kafka_cpu_max_pct_max")),
                kafka_mem_max=format_bytes_in_mib(row.get("kafka_mem_max_bytes_max")),
            )
        )

    if mode_comparison is not None:
        comparison_rows = mode_comparison.get("rows", [])
        if isinstance(comparison_rows, list) and comparison_rows:
            lines.extend(
                [
                    "",
                    "## Matched HTTP vs Kafka Delta",
                    "",
                    f"- matched comparison run: {mode_comparison.get('matched_results_name', 'unknown')} ({mode_comparison.get('matched_delivery_mode', 'unknown')})",
                    "- comparison basis: same git SHA, host, workload, budgets, concurrency, and shared Kafka producer tuning",
                    "",
                    "| service | http-only req/s avg | kafka mode | kafka req/s avg | http-only avg ms | kafka avg ms | est. added Kafka latency (ms) | latency x vs http-only | throughput x http-only/kafka |",
                    "|---|---:|---|---:|---:|---:|---:|---:|---:|",
                ]
            )

            for row in comparison_rows:
                lines.append(
                    "| {service} | {http_only_req_s_avg} | {kafka_delivery_mode} | {kafka_req_s_avg} | {http_only_avg_ms} | {kafka_avg_ms} | {added_latency} | {latency_multiplier} | {throughput_ratio} |".format(
                        service=row["service"],
                        http_only_req_s_avg=format_float(row.get("http_only_req_s_avg")),
                        kafka_delivery_mode=row.get("kafka_delivery_mode", "unknown"),
                        kafka_req_s_avg=format_float(row.get("kafka_req_s_avg")),
                        http_only_avg_ms=format_float(row.get("http_only_avg_ms")),
                        kafka_avg_ms=format_float(row.get("kafka_avg_ms")),
                        added_latency=format_float(row.get("estimated_added_kafka_latency_ms")),
                        latency_multiplier=format_float(row.get("latency_multiplier_vs_http_only")),
                        throughput_ratio=format_float(row.get("throughput_ratio_http_only_vs_kafka")),
                    )
                )

    path.write_text("\n".join(lines) + "\n")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: collate-benchmark-results.py <results-dir>", file=sys.stderr)
        return 1

    results_dir = Path(sys.argv[1]).resolve()
    meta = load_meta(results_dir / "run-meta.txt")
    rows = service_run_rows(results_dir)
    aggregates = aggregate_rows(rows)
    mode_comparison = build_mode_comparison(results_dir, meta, aggregates)

    write_csv(results_dir / "runs.csv", rows)
    write_csv(results_dir / "summary.csv", aggregates)
    if mode_comparison is not None:
        comparison_rows = mode_comparison.get("rows")
        if isinstance(comparison_rows, list):
            write_csv(results_dir / "mode-comparison.csv", comparison_rows)
    write_summary_markdown(results_dir / "summary.md", meta, aggregates, mode_comparison)

    payload = {
        "meta": meta,
        "runs": rows,
        "summary": aggregates,
        "mode_comparison": mode_comparison,
    }
    (results_dir / "summary.json").write_text(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
