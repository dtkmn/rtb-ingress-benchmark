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
PUBLISHED_SNAPSHOT_IDS_PATH = SITE_SRC_DIR / "data" / "published-snapshots.json"

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
TRUTHY_VALUES = {"1", "true", "yes", "on"}
PRIMARY_METRIC_LABELS = {
    "req_per_measured_stack_core": "req/s / measured stack avg core",
    "req_per_cpu_limit": "req/s / receiver CPU limit",
}
STACK_CORE_KEYS = (
    "http_reqs_per_stack_cpu_avg_core_avg",
    "http_reqs_per_measured_stack_avg_core",
    "http_reqs_per_measured_stack_core_avg",
)
STACK_MEMORY_KEYS = (
    "http_reqs_per_stack_mem_avg_gib_avg",
    "http_reqs_per_measured_stack_avg_gib",
    "http_reqs_per_measured_stack_gib_avg",
)
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


def env_flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in TRUTHY_VALUES


def allow_dirty_published_snapshots() -> bool:
    return env_flag("BENCHMARK_SITE_ALLOW_DIRTY_PUBLISH")


def allow_legacy_unknown_dirty_snapshots() -> bool:
    return env_flag("BENCHMARK_SITE_ALLOW_LEGACY_UNKNOWN_DIRTY")


def published_git_dirty_state(snapshot: dict[str, object]) -> str:
    raw_value = str(snapshot.get("git_dirty", "")).strip().lower()
    if raw_value == "false":
        return "clean"
    if raw_value == "true":
        return "dirty"
    return "unknown"


def require_clean_published_snapshot(snapshot: dict[str, object], source: str) -> None:
    snapshot_id = snapshot.get("id", "unknown")
    git_state = published_git_dirty_state(snapshot)
    if git_state == "clean":
        return
    if git_state == "dirty":
        if allow_dirty_published_snapshots():
            return
        raise RuntimeError(
            f"Refusing to publish dirty benchmark snapshot {snapshot_id} from {source}; "
            "set BENCHMARK_SITE_ALLOW_DIRTY_PUBLISH=1 only for an explicitly marked "
            "non-baseline preview."
        )
    if allow_legacy_unknown_dirty_snapshots():
        return
    raise RuntimeError(
        f"Refusing to publish benchmark snapshot {snapshot_id} from {source} because "
        "git_dirty is missing or unknown; use the committed site-data fallback for "
        "legacy baselines, or set BENCHMARK_SITE_ALLOW_LEGACY_UNKNOWN_DIRTY=1 only "
        "when intentionally preserving a pre-metadata published result."
    )


def published_existing_snapshot(snapshot: dict[str, object], snapshot_id: str) -> dict[str, object]:
    existing_snapshot = dict(snapshot)
    existing_snapshot["publication_state"] = "published"
    if published_git_dirty_state(existing_snapshot) == "dirty":
        require_clean_published_snapshot(
            existing_snapshot, f"committed site data snapshot {snapshot_id}"
        )
    return existing_snapshot


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


def first_float_value(row: dict, keys: tuple[str, ...]) -> float | None:
    for key in keys:
        value = row.get(key)
        if value not in (None, ""):
            return float(value)
    return None


def metric_value(row: dict[str, object], key: str) -> float:
    value = row.get(key)
    if value in (None, ""):
        return 0.0
    return float(value)


def select_primary_metric_key(rows: list[dict[str, object]]) -> str:
    if any(metric_value(row, "req_per_measured_stack_core") > 0 for row in rows):
        return "req_per_measured_stack_core"
    return "req_per_cpu_limit"


def primary_metric_label(metric_key: str) -> str:
    return PRIMARY_METRIC_LABELS.get(metric_key, metric_key)


def build_latest_by_mode(snapshots: list[dict[str, object]]) -> dict[str, str]:
    latest_by_mode: dict[str, str] = {}
    published_snapshots = [
        snapshot
        for snapshot in snapshots
        if snapshot.get("publication_state", "published") == "published"
    ]
    for mode in MODE_ORDER:
        for snapshot in published_snapshots:
            if snapshot["mode"] == mode:
                latest_by_mode[mode] = str(snapshot["id"])
                break
    return latest_by_mode


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
        "req_per_measured_stack_core": first_float_value(row, STACK_CORE_KEYS),
        "req_per_measured_stack_gib": first_float_value(row, STACK_MEMORY_KEYS),
        "mem_avg_mib": float_value(row, "receiver_mem_avg_bytes_avg") / (1024**2),
        "cpu_avg_pct": float_value(row, "receiver_cpu_avg_pct_avg"),
    }


def metric_card(label: str, row: dict[str, object], metric: str) -> dict[str, object]:
    return {
        "label": label,
        "service_label": row["service_label"],
        "metric": metric,
    }


def build_cards(
    rows: list[dict[str, object]], primary_key: str
) -> dict[str, dict[str, object]]:
    top_throughput = max(rows, key=lambda row: float(row["req_s"]))
    best_p95 = min(rows, key=lambda row: float(row["p95_ms"]))
    best_efficiency = max(rows, key=lambda row: metric_value(row, primary_key))
    lowest_memory = min(rows, key=lambda row: float(row["mem_avg_mib"]))

    return {
        "top_throughput": metric_card(
            "Top throughput", top_throughput, f"{float(top_throughput['req_s']):.2f} req/s"
        ),
        "best_p95": metric_card(
            "Best p95", best_p95, f"{float(best_p95['p95_ms']):.2f} ms"
        ),
        "best_efficiency": metric_card(
            f"Best {primary_metric_label(primary_key)}",
            best_efficiency,
            f"{metric_value(best_efficiency, primary_key):.2f}",
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


def build_snapshot(
    summary_path: Path,
    publication_state: str = "published",
    source_kind: str = "result-summary",
) -> dict[str, object]:
    payload = json.loads(summary_path.read_text())
    meta = payload["meta"]
    timestamp = parse_timestamp(meta.get("timestamp"), summary_path.parent.name)
    raw_rows = payload["summary"]
    rows = [simplify_summary_row(row) for row in raw_rows]
    rows.sort(key=lambda row: float(row["req_s"]), reverse=True)
    primary_key = select_primary_metric_key(rows)
    primary_row = max(rows, key=lambda row: metric_value(row, primary_key))

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
        "publication_state": publication_state,
        "source_kind": source_kind,
        "benchmark_preset": meta.get("benchmark_preset", ""),
        "fairness_profile": meta.get("fairness_profile", ""),
        "git_sha": meta.get("git_sha", ""),
        "git_sha_short": meta.get("git_sha", "")[:7],
        "git_dirty": meta.get("git_dirty", ""),
        "quarkus_platform_version": meta.get("quarkus_platform_version", ""),
        "commit_url": repo_commit_url(meta.get("git_sha", "")),
        "services_count": len(services) or len(rows),
        "services": services,
        "workload": format_workload(meta),
        "budget": format_budget(meta),
        "primary_metric_key": primary_key,
        "primary_metric_label": primary_metric_label(primary_key),
        "top_primary_service_label": primary_row["service_label"],
        "top_primary_metric": metric_value(primary_row, primary_key),
        "cards": build_cards(rows, primary_key),
        "rows": rows,
        "top_service_label": top_row["service_label"],
        "top_req_s": top_row["req_s"],
        "interpretation": interpretation,
        "mode_comparison": build_mode_comparison(meta.get("delivery_mode", ""), payload.get("mode_comparison")),
    }


def normalize_snapshot(
    snapshot: dict[str, object],
    publication_state: str = "published",
    source_kind: str = "committed-site-data",
) -> dict[str, object]:
    normalized = dict(snapshot)
    normalized.setdefault("publication_state", publication_state)
    normalized.setdefault("source_kind", source_kind)
    normalized.setdefault("benchmark_preset", "")
    normalized.setdefault("fairness_profile", "")

    rows = [dict(row) for row in normalized.get("rows", [])]
    for row in rows:
        row.setdefault("req_per_measured_stack_core", None)
        row.setdefault("req_per_measured_stack_gib", None)
    normalized["rows"] = rows

    if rows:
        primary_key = str(normalized.get("primary_metric_key") or select_primary_metric_key(rows))
        primary_row = max(rows, key=lambda row: metric_value(row, primary_key))
        normalized["primary_metric_key"] = primary_key
        normalized["primary_metric_label"] = primary_metric_label(primary_key)
        normalized["top_primary_service_label"] = primary_row["service_label"]
        normalized["top_primary_metric"] = metric_value(primary_row, primary_key)

        cards = dict(normalized.get("cards", {}))
        best_efficiency = cards.get("best_efficiency")
        if isinstance(best_efficiency, dict):
            best_efficiency["label"] = f"Best {primary_metric_label(primary_key)}"
            best_efficiency["service_label"] = primary_row["service_label"]
            best_efficiency["metric"] = f"{metric_value(primary_row, primary_key):.2f}"
        normalized["cards"] = cards

    return normalized


def normalize_site_data(
    site_data: dict[str, object], snapshot_ids: list[str] | None = None
) -> dict[str, object]:
    normalized = dict(site_data)
    snapshots = [
        normalize_snapshot(snapshot)
        for snapshot in normalized.get("snapshots", [])
        if isinstance(snapshot, dict)
    ]
    if snapshot_ids is not None:
        allowed_ids = set(snapshot_ids)
        snapshots_by_id = {str(snapshot["id"]): snapshot for snapshot in snapshots}
        missing = [snapshot_id for snapshot_id in snapshot_ids if snapshot_id not in snapshots_by_id]
        if missing:
            missing_list = ", ".join(missing)
            raise RuntimeError(
                "Published snapshot IDs are missing from the committed site data: "
                f"{missing_list}"
            )

        extra_published = [
            str(snapshot["id"])
            for snapshot in snapshots
            if snapshot.get("publication_state", "published") == "published"
            and str(snapshot["id"]) not in allowed_ids
        ]
        if extra_published:
            extra_list = ", ".join(extra_published)
            raise RuntimeError(
                "Committed site data contains published snapshots not listed in "
                f"{PUBLISHED_SNAPSHOT_IDS_PATH}: {extra_list}"
            )

        snapshots = [snapshots_by_id[snapshot_id] for snapshot_id in snapshot_ids]

    snapshots.sort(key=lambda snap: str(snap["timestamp"]), reverse=True)
    normalized["snapshots"] = snapshots
    normalized["latest_by_mode"] = build_latest_by_mode(snapshots)
    return normalized


def load_existing_site_data() -> dict[str, object] | None:
    if not SITE_SOURCE_DATA_PATH.exists():
        return None
    return json.loads(SITE_SOURCE_DATA_PATH.read_text())


def load_published_snapshot_ids() -> list[str]:
    env_value = os.environ.get("BENCHMARK_SITE_PUBLISHED_SNAPSHOT_IDS", "").strip()
    if env_value:
        return [part for part in env_value.replace(",", " ").split() if part]

    if not PUBLISHED_SNAPSHOT_IDS_PATH.exists():
        raise RuntimeError(
            f"Published snapshot manifest is missing: {PUBLISHED_SNAPSHOT_IDS_PATH}"
        )

    manifest = json.loads(PUBLISHED_SNAPSHOT_IDS_PATH.read_text())
    snapshot_ids = manifest.get("snapshot_ids", [])
    if not isinstance(snapshot_ids, list) or not all(
        isinstance(snapshot_id, str) for snapshot_id in snapshot_ids
    ):
        raise RuntimeError(
            f"{PUBLISHED_SNAPSHOT_IDS_PATH} must contain a string array named snapshot_ids"
        )
    return snapshot_ids


def base_site_data(generated_at: str | None = None) -> dict[str, object]:
    return {
        "generated_at": generated_at or datetime.now().astimezone().isoformat(),
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
    }


def build_published_site_data() -> dict[str, object]:
    snapshot_ids = load_published_snapshot_ids()
    if not snapshot_ids:
        raise RuntimeError("No published snapshot IDs are configured.")

    existing_data = load_existing_site_data()
    existing_snapshots = {}
    if existing_data:
        existing_snapshots = {
            str(snapshot["id"]): normalize_snapshot(snapshot)
            for snapshot in existing_data.get("snapshots", [])
            if isinstance(snapshot, dict) and snapshot.get("id")
        }

    snapshots = []
    missing = []
    rebuilt_from_results = False
    for snapshot_id in snapshot_ids:
        summary_path = RESULTS_DIR / snapshot_id / "summary.json"
        if summary_path.exists():
            snapshot = build_snapshot(summary_path)
            if (
                published_git_dirty_state(snapshot) == "unknown"
                and snapshot_id in existing_snapshots
            ):
                snapshots.append(
                    published_existing_snapshot(existing_snapshots[snapshot_id], snapshot_id)
                )
                continue
            require_clean_published_snapshot(snapshot, str(summary_path))
            snapshots.append(snapshot)
            rebuilt_from_results = True
            continue
        if snapshot_id in existing_snapshots:
            snapshots.append(
                published_existing_snapshot(existing_snapshots[snapshot_id], snapshot_id)
            )
            continue
        missing.append(snapshot_id)

    if missing:
        missing_list = ", ".join(missing)
        raise RuntimeError(
            "Published snapshot IDs are missing from both results/ and the committed "
            f"site data: {missing_list}"
        )

    snapshots.sort(key=lambda snap: str(snap["timestamp"]), reverse=True)
    generated_at = (
        datetime.now().astimezone().isoformat()
        if rebuilt_from_results or not existing_data
        else str(existing_data.get("generated_at", ""))
    )
    site_data = base_site_data(generated_at=generated_at)
    site_data["latest_by_mode"] = build_latest_by_mode(snapshots)
    site_data["snapshots"] = snapshots
    return site_data


def load_site_data(refresh_source_data: bool = False) -> dict[str, object]:
    if refresh_source_data:
        site_data = build_published_site_data()
        SITE_SOURCE_DATA_PATH.parent.mkdir(parents=True, exist_ok=True)
        SITE_SOURCE_DATA_PATH.write_text(json.dumps(site_data, indent=2))
        return site_data

    existing_data = load_existing_site_data()
    if existing_data:
        return normalize_site_data(existing_data, snapshot_ids=load_published_snapshot_ids())

    raise RuntimeError(
        "site/src/data/site-data.json does not exist. Set BENCHMARK_SITE_REFRESH_DATA=1 "
        "after configuring site/src/data/published-snapshots.json."
    )


def build_site() -> None:
    if SITE_DIST_DIR.exists():
        shutil.rmtree(SITE_DIST_DIR)
    shutil.copytree(SITE_SRC_DIR, SITE_DIST_DIR)

    site_data = load_site_data(refresh_source_data=env_flag("BENCHMARK_SITE_REFRESH_DATA"))
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
