# Benchmark History

This page keeps the dated benchmark trail so the README can stay focused on the current verified baseline.

## Update Policy

- Update the README snapshot only when you have a comparable, trusted baseline.
- Prefer adding a new history entry when there is a meaningful runtime/framework/library upgrade or an intentional benchmark-method change.
- Skip a month rather than publishing noise.
- If hardware, workload shape, or fairness controls changed, say so explicitly and do not imply direct comparability.

## Current Baseline

- **Snapshot date:** 2026-03-12
- **Git SHA:** `7432aed42fb6cfee1d6844334ea4ba3422837a06`
- **Services:** Quarkus JVM, Quarkus Native, Go, Rust, Python/FastAPI, Spring WebFlux, Spring virtual threads, Node/Fastify
- **Workload:** `100` VUs for `30s` with `10s` warmup
- **Budget:** receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g`
- **Reference runs:** [`http-only`](../results/20260312-190020/summary.md), [`confirm`](../results/20260312-184014/summary.md), [`enqueue`](../results/20260312-192216/summary.md)

Current baseline notes:

- `http-only` is the cleanest read on framework/runtime overhead for this setup.
- `confirm` is the better read on conservative ingress behavior with Kafka delivery confirmation in the request path.
- The matched delta in the `http-only` summary shows that some services retained far more of their throughput once Kafka confirmation was added, even when their raw `http-only` rank was lower.

## Snapshot Log

| Snapshot Date | Git SHA | Modes | Services | Workload / Budget | Result Links | Notes |
|---|---|---|---|---|---|---|
| 2026-03-12 | `7432aed` | `http-only`, `confirm`, `enqueue` | 8 receiver lanes | `100` VUs, `30s`, receiver `2.0 CPU / 768m`, Kafka `2.0 CPU / 1g` | [`http-only`](../results/20260312-190020/summary.md) · [`confirm`](../results/20260312-184014/summary.md) · [`enqueue`](../results/20260312-192216/summary.md) | First baseline with `spring-virtual-receiver`; matched `http-only` vs `confirm` delta available. |

## How To Add The Next Snapshot

1. Run a comparable baseline set, ideally `http-only` and `confirm`, plus `enqueue` if you want the full ingress story.
2. Verify the run metadata is stable enough to compare against the previous entry.
3. Add a new row at the top of the snapshot log with:
   - date
   - git SHA
   - modes
   - services included
   - workload and resource budget
   - links to exact `summary.md` files
   - one short note about what changed
4. Update the README snapshot only after the new run is the best current baseline.
