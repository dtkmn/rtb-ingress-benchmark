#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import shutil
from datetime import datetime
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
RESULTS_DIR = ROOT_DIR / "results"
SITE_SRC_DIR = ROOT_DIR / "site" / "src"
SITE_DIST_DIR = ROOT_DIR / "site" / "dist"
SITE_SOURCE_DATA_PATH = SITE_SRC_DIR / "data" / "site-data.json"

REPO_URL = os.environ.get(
    "BENCHMARK_SITE_REPO_URL", "https://github.com/dtkmn/rtb-ingress-benchmark"
).rstrip("/")
REPO_REF = os.environ.get("BENCHMARK_SITE_REPO_REF", "main")

MODE_ORDER = ["confirm", "http-only", "enqueue"]
MODE_LABELS = {
    "confirm": "Confirm",
    "http-only": "HTTP-only",
    "enqueue": "Enqueue",
}
MODE_DESCRIPTIONS = {
    "confirm": "Combined HTTP handling plus Kafka delivery confirmation. Useful for conservative ingress-path behavior, not pure framework speed.",
    "http-only": "Isolates parsing, validation, filtering, and response handling without Kafka in the path.",
    "enqueue": "Combined HTTP handling plus Kafka client enqueue/fire-and-forget behavior. Faster than confirm, but not equivalent to durable end-to-end delivery.",
}
SERVICE_LABELS = {
    "quarkus-receiver": "Quarkus JVM",
    "quarkus-receiver-native": "Quarkus Native",
    "go-receiver": "Go",
    "rust-receiver": "Rust",
    "python-receiver": "Python / FastAPI",
    "spring-receiver": "Spring WebFlux",
    "spring-virtual-receiver": "Spring Virtual Threads",
    "node-receiver": "Node / Fastify",
}


def parse_timestamp(raw: str | None, fallback: str) -> datetime:
    if raw:
        try:
            return datetime.fromisoformat(raw)
        except ValueError:
            pass
    return datetime.strptime(fallback, "%Y%m%d-%H%M%S")


def float_value(row: dict, key: str, default: float = 0.0) -> float:
    value = row.get(key, default)
    if value in (None, ""):
        return default
    return float(value)


def format_workload(meta: dict[str, str]) -> str:
    rate = meta.get("rate", "0")
    if rate and rate != "0":
        return (
            f"{rate} req/s for {meta.get('duration', 'unknown')}"
            f" (warmup {meta.get('warmup_duration', 'unknown')})"
        )
    return (
        f"{meta.get('vus', 'unknown')} VUs for {meta.get('duration', 'unknown')}"
        f" (warmup {meta.get('warmup_duration', 'unknown')})"
    )


def format_budget(meta: dict[str, str]) -> str:
    return (
        f"receiver {meta.get('receiver_cpus', 'unknown')} CPU / {meta.get('receiver_memory', 'unknown')}, "
        f"Kafka {meta.get('kafka_cpus', 'unknown')} CPU / {meta.get('kafka_memory', 'unknown')}"
    )


def repo_blob_url(path: str) -> str:
    return f"{REPO_URL}/blob/{REPO_REF}/{path}"


def repo_commit_url(commit_sha: str) -> str:
    if not commit_sha:
        return REPO_URL
    return f"{REPO_URL}/commit/{commit_sha}"


def simplify_summary_row(row: dict) -> dict[str, object]:
    return {
        "service_id": row["service"],
        "service_label": SERVICE_LABELS.get(str(row["service"]), str(row["service"])),
        "runs": int(float_value(row, "runs", 0.0)),
        "req_s": float_value(row, "http_reqs_rate_avg"),
        "p95_ms": float_value(row, "http_req_duration_p95_ms_avg"),
        "req_per_cpu_limit": float_value(row, "http_reqs_per_receiver_cpu_limit_avg"),
        "mem_avg_mib": float_value(row, "receiver_mem_avg_bytes_avg") / (1024**2),
        "cpu_avg_pct": float_value(row, "receiver_cpu_avg_pct_avg"),
    }


def metric_card(label: str, row: dict[str, object], metric: str) -> dict[str, object]:
    return {
        "label": label,
        "service_label": row["service_label"],
        "metric": metric,
    }


def build_cards(rows: list[dict[str, object]]) -> dict[str, dict[str, object]]:
    top_throughput = max(rows, key=lambda row: float(row["req_s"]))
    best_p95 = min(rows, key=lambda row: float(row["p95_ms"]))
    best_efficiency = max(rows, key=lambda row: float(row["req_per_cpu_limit"]))
    lowest_memory = min(rows, key=lambda row: float(row["mem_avg_mib"]))

    return {
        "top_throughput": metric_card(
            "Top throughput", top_throughput, f"{float(top_throughput['req_s']):.2f} req/s"
        ),
        "best_p95": metric_card(
            "Best p95", best_p95, f"{float(best_p95['p95_ms']):.2f} ms"
        ),
        "best_efficiency": metric_card(
            "Best req/s per CPU limit",
            best_efficiency,
            f"{float(best_efficiency['req_per_cpu_limit']):.2f}",
        ),
        "lowest_memory": metric_card(
            "Lowest memory", lowest_memory, f"{float(lowest_memory['mem_avg_mib']):.2f} MiB"
        ),
    }


def build_mode_comparison(snapshot_mode: str, mode_comparison: dict | None) -> dict | None:
    if not mode_comparison:
        return None

    rows = []
    current_rank_key = "http_only_rank" if snapshot_mode == "http-only" else "kafka_mode_rank"
    paired_rank_key = "kafka_mode_rank" if snapshot_mode == "http-only" else "http_only_rank"

    for row in mode_comparison.get("rows", []):
        rows.append(
            {
                "service_label": SERVICE_LABELS.get(row["service"], row["service"]),
                "current_rank": int(row.get(current_rank_key) or 0),
                "paired_rank": int(row.get(paired_rank_key) or 0),
                "retained_pct": float(row.get("throughput_retained_vs_http_only", 0.0)) * 100.0,
                "lost_pct": float(row.get("throughput_lost_vs_http_only", 0.0)) * 100.0,
                "added_latency_ms": float(row.get("estimated_added_kafka_latency_ms", 0.0)),
                "http_only_req_s": float(row.get("http_only_req_s_avg", 0.0)),
                "paired_req_s": float(row.get("kafka_req_s_avg", 0.0)),
            }
        )

    rows.sort(key=lambda row: (row["current_rank"], row["service_label"]))
    return {
        "matched_results_name": mode_comparison.get("matched_results_name"),
        "matched_delivery_mode": mode_comparison.get("matched_delivery_mode"),
        "rows": rows,
    }


def build_snapshot(summary_path: Path) -> dict[str, object]:
    payload = json.loads(summary_path.read_text())
    meta = payload["meta"]
    timestamp = parse_timestamp(meta.get("timestamp"), summary_path.parent.name)
    raw_rows = payload["summary"]
    rows = [simplify_summary_row(row) for row in raw_rows]
    rows.sort(key=lambda row: float(row["req_s"]), reverse=True)

    top_row = rows[0]
    interpretation = [
        MODE_DESCRIPTIONS.get(meta.get("delivery_mode", ""), "Mode-specific benchmark snapshot."),
        "Rankings are environment-specific and should not be treated as universal backend rankings.",
    ]
    if payload.get("mode_comparison"):
        interpretation.append(
            "Matched delta data is available below, so you can see how much throughput changed relative to the paired mode."
        )
    elif meta.get("delivery_mode") in {"confirm", "enqueue"}:
        interpretation.append(
            "No matched HTTP-only comparison is available for this snapshot, so raw rank is easier to misread."
        )

    snapshot_id = summary_path.parent.name
    services = meta.get("services", "").split()
    return {
        "id": snapshot_id,
        "timestamp": timestamp.isoformat(),
        "date_label": timestamp.strftime("%Y-%m-%d"),
        "time_label": timestamp.strftime("%H:%M"),
        "mode": meta.get("delivery_mode", "unknown"),
        "mode_label": MODE_LABELS.get(meta.get("delivery_mode", ""), meta.get("delivery_mode", "unknown")),
        "git_sha": meta.get("git_sha", ""),
        "git_sha_short": meta.get("git_sha", "")[:7],
        "commit_url": repo_commit_url(meta.get("git_sha", "")),
        "services_count": len(services) or len(rows),
        "services": services,
        "workload": format_workload(meta),
        "budget": format_budget(meta),
        "cards": build_cards(rows),
        "rows": rows,
        "top_service_label": top_row["service_label"],
        "top_req_s": top_row["req_s"],
        "interpretation": interpretation,
        "mode_comparison": build_mode_comparison(meta.get("delivery_mode", ""), payload.get("mode_comparison")),
    }


def build_site_data() -> dict[str, object]:
    snapshots = []
    for summary_path in sorted(RESULTS_DIR.glob("*/summary.json")):
        try:
            snapshots.append(build_snapshot(summary_path))
        except Exception as exc:  # pragma: no cover - defensive; build should continue
            print(f"Skipping {summary_path}: {exc}")

    snapshots.sort(key=lambda snap: snap["timestamp"], reverse=True)

    latest_by_mode: dict[str, str] = {}
    for mode in MODE_ORDER:
        for snapshot in snapshots:
            if snapshot["mode"] == mode:
                latest_by_mode[mode] = snapshot["id"]
                break

    return {
        "generated_at": datetime.now().astimezone().isoformat(),
        "repo": {
            "name": "rtb-ingress-benchmark",
            "url": REPO_URL,
            "blob_base": f"{REPO_URL}/blob/{REPO_REF}",
            "tree_base": f"{REPO_URL}/tree/{REPO_REF}",
        },
        "docs": {
            "contract_url": repo_blob_url("docs/BENCHMARK_CONTRACT.md"),
            "history_url": repo_blob_url("docs/BENCHMARK_HISTORY.md"),
            "site_data_url": repo_blob_url("site/src/data/site-data.json"),
        },
        "mode_order": MODE_ORDER,
        "latest_by_mode": latest_by_mode,
        "snapshots": snapshots,
    }


def load_site_data() -> dict[str, object]:
    site_data = build_site_data()
    if site_data["snapshots"]:
        SITE_SOURCE_DATA_PATH.parent.mkdir(parents=True, exist_ok=True)
        SITE_SOURCE_DATA_PATH.write_text(json.dumps(site_data, indent=2))
        return site_data

    if SITE_SOURCE_DATA_PATH.exists():
        return json.loads(SITE_SOURCE_DATA_PATH.read_text())

    raise RuntimeError(
        "No benchmark summaries were found under results/ and site/src/data/site-data.json does not exist."
    )


def build_site() -> None:
    if SITE_DIST_DIR.exists():
        shutil.rmtree(SITE_DIST_DIR)
    shutil.copytree(SITE_SRC_DIR, SITE_DIST_DIR)

    site_data = load_site_data()
    data_path = SITE_DIST_DIR / "data" / "site-data.json"
    data_path.parent.mkdir(parents=True, exist_ok=True)
    data_path.write_text(json.dumps(site_data, indent=2))
    (SITE_DIST_DIR / ".nojekyll").write_text("")


def main() -> int:
    build_site()
    print(f"Wrote benchmark site to {SITE_DIST_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
