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

- **Services:** Quarkus JVM, Quarkus Native, Go, Rust, Python/FastAPI, Spring WebFlux, Spring virtual threads, Node/Fastify
- **Workload:** `100` VUs for `30s` with `10s` warmup
- **Budget:** receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g`
- **Dashboard source of truth:** [`site/src/data/site-data.json`](../site/src/data/site-data.json)

| Mode | Snapshot | Git SHA | Current top throughput |
|---|---|---|---|
| `confirm` | `20260326-211147` | `a2c1f1d` | Python / FastAPI, 8125.79 req/s |
| `http-only` | `20260312-190020` | `7432aed` | Quarkus JVM, 29987.52 req/s |
| `enqueue` | `20260312-192216` | `a2c1f1d` | Quarkus JVM, 24491.75 req/s |

Current snapshot notes:

- `http-only` is the cleanest read on framework/runtime overhead for this setup.
- `confirm` is the better read on conservative ingress behavior with Kafka delivery confirmation in the request path.
- The latest published `confirm` snapshot is newer than the latest published `http-only` and `enqueue` snapshots, so do not treat the three modes as a single synchronized baseline.
- Matched mode deltas are useful only when the compared runs share compatible metadata.

## Snapshot Log

| Snapshot Date | Git SHA | Modes | Services | Workload / Budget | Result Links | Notes |
|---|---|---|---|---|---|---|
| 2026-03-26 | `a2c1f1d` | `confirm` | 8 receiver lanes | `100` VUs, `30s`, receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g` | Dashboard data: [`site-data.json`](../site/src/data/site-data.json) | Latest published `confirm` snapshot; Python / FastAPI led raw throughput in this run. |
| 2026-03-12 | `7432aed` | `http-only`, `confirm`, `enqueue` | 8 receiver lanes | `100` VUs, `30s`, receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g` | [`http-only`](../results/20260312-190020/summary.md) · [`confirm`](../results/20260312-184014/summary.md) · [`enqueue`](../results/20260312-192216/summary.md) | First baseline with `spring-virtual-receiver`; matched `http-only` vs `confirm` delta available. |

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
5. Update the README snapshot only after the new run is the best current published baseline.
