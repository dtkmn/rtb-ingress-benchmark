# Benchmark History

This page keeps the dated benchmark trail so the README can stay focused on the current published snapshots.

## Update Policy

- Treat local runs as exploratory by default. Do not promote them automatically just because they are recent.
- Update the README snapshot only when you have a comparable, trusted baseline that is worth calling out publicly.
- Prefer adding a new history entry only when there is a meaningful trigger:
  - runtime/framework/library upgrade
  - request-handling or validation-path change
  - Kafka client or producer-tuning change
  - new service/runtime added to the matrix
  - intentional benchmark-method change
- Skip a month rather than publishing noise.
- If hardware, workload shape, benchmark preset, or fairness controls changed, say so explicitly and do not imply direct comparability.
- A result is usually worth publishing only when the change is material enough to interpret, for example a visible throughput, latency, efficiency, or cross-mode rank shift.

## Current Published Snapshots

- **Services:** May 3 `confirm` uses 7 strict-compatible receiver lanes; May 3 `http-only` and `enqueue` use all 8 receiver lanes
- **Workload:** `100` VUs for `30s` with `10s` warmup
- **Budget:** receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g`
- **Dashboard source of truth:** [`site/src/data/site-data.json`](../site/src/data/site-data.json)
- **Published snapshot allowlist:** [`site/src/data/published-snapshots.json`](../site/src/data/published-snapshots.json)

| Mode | Snapshot | Git SHA | Primary result |
|---|---|---|---|
| `confirm` | `20260503-003926` | `740d25a` | Spring WebFlux, 15015.54 req/s/core |
| `http-only` | `20260503-005939` | `740d25a` | Rust / Actix, 38480.29 req/s/core |
| `enqueue` | `20260503-012856` | `740d25a` | Rust / Actix, 16677.20 req/s/core |

Current snapshot notes:

- `http-only` is the cleanest read on framework/runtime overhead for this setup.
- `confirm` is the better read on conservative ingress behavior with Kafka delivery confirmation in the request path.
- The May 3 published set covers all three modes, but `confirm` uses `strict-1` while `http-only` and `enqueue` use `fixed-envelope`; do not imply one synchronized topology across all three rows.
- Matched mode deltas are useful only when the compared runs share compatible metadata.
- The older published March snapshots predate explicit `benchmark_preset` and `fairness_profile` metadata; do not retrofit `strict-1` labels onto them.

## Pending Refresh Decisions

| Date | Change | Decision | Follow-through |
|---|---|---|---|
| 2026-07-03 | Quarkus platform now records as `3.37.1` for `quarkus-receiver` and `quarkus-sinker`, alongside benchmark publication metadata/script changes | Dirty strict-1 `confirm` reconnaissance run `20260703-122655` shows material movement: Rust leads at 19312.93 req/s/core and 7022.42 raw req/s; Spring WebFlux is third by CPU-normalized result at 11422.34 req/s/core. This is enough to justify a clean rerun, but not enough to publish because `git_dirty=true`. | Keep the May 3 published snapshots in place. After committing or separating the dependency/script/doc changes, rerun `BENCHMARK_PRESET=strict-1 scripts/run-benchmark-matrix.sh` from a clean worktree; promote only if the clean result repeats the material ranking shift and passes the publication checklist. |

## Snapshot Log

| Snapshot Date | Git SHA | Modes | Services | Workload / Budget | Result Links | Notes |
|---|---|---|---|---|---|---|
| 2026-05-03 | `740d25a` | `confirm`, `http-only`, `enqueue` | 7 strict-compatible receiver lanes for `confirm`; 8 receiver lanes for `http-only` and `enqueue` | `100` VUs, `30s`, receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g` | [`confirm`](../results/20260503-003926/summary.md) · [`http-only`](../results/20260503-005939/summary.md) · [`enqueue`](../results/20260503-012856/summary.md) | Latest published baseline set. `confirm` is strict-1; `http-only` and `enqueue` are fixed-envelope. |
| 2026-03-26 | `a2c1f1d` | `confirm` | 8 receiver lanes | `100` VUs, `30s`, receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g` | Dashboard data: [`site-data.json`](../site/src/data/site-data.json) | Previous published `confirm` snapshot; Python / FastAPI led raw throughput in this run. |
| 2026-03-12 | `7432aed` | `http-only`, `confirm`, `enqueue` | 8 receiver lanes | `100` VUs, `30s`, receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g` | [`http-only`](../results/20260312-190020/summary.md) · [`confirm`](../results/20260312-184014/summary.md) · [`enqueue`](../results/20260312-192216/summary.md) | First baseline with `spring-virtual-receiver`; matched `http-only` vs `confirm` delta available. |

## Local Exploratory Runs Not Published

These runs are useful notes, not dashboard baselines. The trap is obvious: recency feels like authority, but it is not authority until the run is comparable, trusted, and intentionally promoted.

| Snapshot Date | Git SHA | Snapshot | Mode | Services | Controls | Result | Publication decision |
|---|---|---|---|---|---|---|---|
| 2026-07-03 | `17b501e` dirty | `20260703-122655` | `confirm` | 7 strict-compatible receiver lanes | `strict-1`, Quarkus platform `3.37.1`, 3 repeats, `100` VUs for `30s`, receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g`, `git_dirty=true` | Rust led both measured stack CPU efficiency at 19312.93 req/s/core and raw throughput at 7022.42 req/s; Spring WebFlux ranked third by CPU-normalized result at 11422.34 req/s/core. | Kept out of published snapshots because the worktree was dirty; use this only as a signal to run a clean confirmation benchmark. |
| 2026-04-24 | `7bdb712` | `20260424-202643` | `confirm` | 8 receiver lanes | Older local fixed-envelope-style run: producer pool `2`, `bids` with 3 partitions, retries `5`, 1 repeat, no explicit preset metadata | Go led raw throughput at 8561.37 req/s; Spring WebFlux led measured stack CPU efficiency at 13396.12 req/s/core. | Kept out of the published baseline because it was exploratory, single-repeat, and not paired with a compatible `http-only` snapshot. |

## How To Add The Next Snapshot

1. Run local benchmarks when there is a meaningful trigger, not because a calendar date arrived.
2. If the exploratory run shows a material change worth publishing, rerun a comparable baseline set, ideally `http-only` and `confirm`, plus `enqueue` if you want the full ingress story.
3. Verify the run metadata is stable enough to compare against the previous entry.
4. Add a new row at the top of the snapshot log with:
   - date
   - git SHA
   - modes
   - services included
   - workload and resource budget
   - links to exact `summary.md` files
   - one short note about what changed
5. Confirm `git_dirty=false` and the intended Quarkus platform version in `run-meta.txt` before treating the run as publishable.
6. Add the exact result directory ID to [`site/src/data/published-snapshots.json`](../site/src/data/published-snapshots.json).
7. Refresh the committed dashboard data with `BENCHMARK_SITE_REFRESH_DATA=1 python3 scripts/build_benchmark_site.py`.
8. Update the README snapshot only after the new run is the best current published baseline.
