# Benchmark Summary

- benchmark preset: custom
- delivery mode: enqueue
- fairness profile: fixed-envelope
- git dirty: false
- Quarkus platform: 3.37.1
- kafka enabled: true
- kafka acks: 0
- kafka topic: bids (3 partitions, rf 1, min ISR 1)
- kafka producer tuning: linger 10 ms, batch 131072 bytes, request timeout 5000 ms, retry backoff 100 ms, retries 5 when supported
- services: quarkus-receiver quarkus-receiver-native go-receiver rust-receiver python-receiver spring-receiver spring-virtual-receiver node-receiver
- workload: 100 VUs for 30s (warmup 10s)
- receiver budget: 2.0 CPU / 768m
- kafka budget: 2.0 CPU / 1g

- interpretation: `enqueue` ranks combined HTTP + Kafka client behavior by CPU-normalized capacity, not pure framework speed.
- interpretation: use the matched `http-only` delta section below to see how much throughput each service retained once Kafka was in the path.

## Capacity Efficiency Ranking

- primary result: `req/s / measured stack avg core` ranks cost-efficient capacity by observed CPU use.
- raw `req/s avg` is a throughput sanity check, not the primary ranking.
- p95/p99 latency and non-2xx/non-204 responses are veto metrics; an efficient result with bad tail latency or errors is not a win.

| rank | service | runs | req/s / measured stack avg core | req/s avg | p95 avg (ms) | req/s / measured stack avg GiB | req/s / receiver CPU limit | req/s / receiver GiB limit |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | rust-receiver | 3 | 27254.87 | 37529.38 | 5.93 | 70200.13 | 18764.69 | 50039.17 |
| 2 | quarkus-receiver | 3 | 17280.18 | 34316.24 | 6.30 | 44625.73 | 17158.12 | 45754.99 |
| 3 | spring-receiver | 3 | 14463.61 | 29412.30 | 7.27 | 36981.47 | 14706.15 | 39216.40 |
| 4 | go-receiver | 3 | 13034.10 | 26389.91 | 8.68 | 41885.96 | 13194.96 | 35186.55 |
| 5 | spring-virtual-receiver | 3 | 12873.46 | 29326.59 | 6.75 | 37238.34 | 14663.30 | 39102.12 |
| 6 | quarkus-receiver-native | 3 | 11555.19 | 24979.00 | 8.76 | 38632.75 | 12489.50 | 33305.33 |
| 7 | python-receiver | 3 | 6880.53 | 14923.42 | 10.08 | 22524.81 | 7461.71 | 19897.89 |
| 8 | node-receiver | 3 | 4638.86 | 16406.65 | 12.36 | 21114.35 | 8203.32 | 21875.53 |

## Resource Footprint

- `docker stats` CPU percentages are per-core. On a 2 CPU container, `200%` means both CPUs were effectively saturated, so values above `100%` are expected.

| service | receiver cpu avg % | receiver cpu max % | receiver mem avg MiB | receiver mem max MiB | kafka cpu max % | kafka mem max MiB |
|---|---:|---:|---:|---:|---:|---:|
| rust-receiver | 101.48 | 117.81 | 18.04 | 18.73 | 200.60 | 616.50 |
| quarkus-receiver | 168.21 | 212.54 | 287.64 | 334.90 | 196.99 | 598.80 |
| spring-receiver | 178.92 | 207.56 | 283.50 | 285.20 | 186.33 | 622.20 |
| go-receiver | 173.55 | 189.03 | 117.86 | 142.70 | 188.39 | 627.90 |
| spring-virtual-receiver | 201.43 | 220.89 | 268.66 | 270.70 | 183.78 | 624.90 |
| quarkus-receiver-native | 190.60 | 209.34 | 137.16 | 152.40 | 170.67 | 616.00 |
| python-receiver | 198.58 | 211.88 | 164.82 | 177.70 | 106.52 | 582.50 |
| node-receiver | 208.80 | 221.09 | 251.94 | 260.90 | 216.76 | 624.50 |

## Matched HTTP vs Kafka Delta

- matched comparison run: 20260704-152658 (http-only)
- comparison basis: clean git state, same git SHA, Quarkus platform, normalized OS/kernel signature, workload, budgets, concurrency, and shared Kafka producer tuning
- read this table before inferring framework quality from `enqueue` or `confirm` rankings.

| service | http-only efficiency rank | current efficiency rank | http-only req/s/core | kafka mode | kafka req/s/core | http-only req/s avg | kafka req/s avg | throughput retained vs http-only | throughput lost vs http-only | est. added Kafka latency (ms) | latency x vs http-only |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| rust-receiver | 1 | 1 | 47233.18 | enqueue | 27254.87 | 39292.74 | 37529.38 | 95.51% | 4.49% | 0.12 | 1.05 |
| quarkus-receiver | 2 | 2 | 25763.10 | enqueue | 17280.18 | 38896.98 | 34316.24 | 88.22% | 11.78% | 0.33 | 1.13 |
| spring-receiver | 4 | 3 | 20611.13 | enqueue | 14463.61 | 34398.22 | 29412.30 | 85.51% | 14.49% | 0.49 | 1.17 |
| go-receiver | 3 | 4 | 25323.19 | enqueue | 13034.10 | 34415.76 | 26389.91 | 76.68% | 23.32% | 0.88 | 1.31 |
| spring-virtual-receiver | 6 | 5 | 16556.38 | enqueue | 12873.46 | 30933.89 | 29326.59 | 94.80% | 5.20% | 0.18 | 1.06 |
| quarkus-receiver-native | 5 | 6 | 16924.31 | enqueue | 11555.19 | 30728.39 | 24979.00 | 81.29% | 18.71% | 0.74 | 1.23 |
| python-receiver | 8 | 7 | 9282.77 | enqueue | 6880.53 | 17866.87 | 14923.42 | 83.53% | 16.47% | 1.10 | 1.20 |
| node-receiver | 7 | 8 | 16177.95 | enqueue | 4638.86 | 29747.36 | 16406.65 | 55.15% | 44.85% | 2.73 | 1.82 |
