# Benchmark Summary

- benchmark preset: custom
- delivery mode: enqueue
- fairness profile: fixed-envelope
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
| 1 | rust-receiver | 3 | 16677.20 | 23377.40 | 10.13 | 42274.96 | 11688.70 | 31169.87 |
| 2 | quarkus-receiver | 3 | 12892.36 | 24915.85 | 9.33 | 31777.09 | 12457.92 | 33221.13 |
| 3 | go-receiver | 3 | 7812.98 | 15908.16 | 14.44 | 24502.83 | 7954.08 | 21210.88 |
| 4 | spring-virtual-receiver | 3 | 7735.88 | 17764.08 | 12.75 | 23105.58 | 8882.04 | 23685.44 |
| 5 | spring-receiver | 3 | 7483.01 | 16516.15 | 13.84 | 20709.07 | 8258.08 | 22021.53 |
| 6 | quarkus-receiver-native | 3 | 7260.87 | 16103.83 | 13.99 | 23788.17 | 8051.91 | 21471.77 |
| 7 | python-receiver | 3 | 4330.05 | 9588.45 | 17.99 | 13998.09 | 4794.22 | 12784.60 |
| 8 | node-receiver | 3 | 2699.16 | 8745.95 | 25.85 | 10631.17 | 4372.97 | 11661.26 |

## Resource Footprint

- `docker stats` CPU percentages are per-core. On a 2 CPU container, `200%` means both CPUs were effectively saturated, so values above `100%` are expected.

| service | receiver cpu avg % | receiver cpu max % | receiver mem avg MiB | receiver mem max MiB | kafka cpu max % | kafka mem max MiB |
|---|---:|---:|---:|---:|---:|---:|
| rust-receiver | 91.18 | 118.44 | 18.00 | 18.87 | 214.77 | 635.30 |
| quarkus-receiver | 152.83 | 216.62 | 268.02 | 306.20 | 205.19 | 637.20 |
| go-receiver | 162.13 | 189.31 | 121.39 | 147.30 | 233.25 | 643.70 |
| spring-virtual-receiver | 189.11 | 228.51 | 225.14 | 228.10 | 202.96 | 638.70 |
| spring-receiver | 179.86 | 218.80 | 256.17 | 257.90 | 213.94 | 635.60 |
| quarkus-receiver-native | 188.92 | 210.42 | 127.78 | 149.50 | 196.06 | 642.90 |
| python-receiver | 197.32 | 217.37 | 145.57 | 158.40 | 202.29 | 635.20 |
| node-receiver | 205.58 | 218.95 | 277.20 | 292.20 | 216.84 | 642.40 |

## Matched HTTP vs Kafka Delta

- matched comparison run: 20260503-005939 (http-only)
- comparison basis: same git SHA, normalized OS/kernel signature, workload, budgets, concurrency, and shared Kafka producer tuning
- read this table before inferring framework quality from `enqueue` or `confirm` rankings.

| service | http-only efficiency rank | current efficiency rank | http-only req/s/core | kafka mode | kafka req/s/core | http-only req/s avg | kafka req/s avg | throughput retained vs http-only | throughput lost vs http-only | est. added Kafka latency (ms) | latency x vs http-only |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| rust-receiver | 1 | 1 | 38480.29 | enqueue | 16677.20 | 26227.59 | 23377.40 | 89.13% | 10.87% | 0.46 | 1.12 |
| quarkus-receiver | 5 | 2 | 12676.83 | enqueue | 12892.36 | 14104.51 | 24915.85 | 176.65% | -76.65% | -3.04 | 0.57 |
| go-receiver | 2 | 3 | 15691.00 | enqueue | 7812.98 | 21528.64 | 15908.16 | 73.89% | 26.11% | 1.51 | 1.32 |
| spring-virtual-receiver | 4 | 4 | 12810.89 | enqueue | 7735.88 | 22261.20 | 17764.08 | 79.80% | 20.20% | 1.17 | 1.26 |
| spring-receiver | 3 | 5 | 13438.10 | enqueue | 7483.01 | 21995.64 | 16516.15 | 75.09% | 24.91% | 1.50 | 1.33 |
| quarkus-receiver-native | 7 | 6 | 7104.61 | enqueue | 7260.87 | 11617.13 | 16103.83 | 138.62% | -38.62% | -2.35 | 0.72 |
| python-receiver | 8 | 7 | 6181.43 | enqueue | 4330.05 | 12060.90 | 9588.45 | 79.50% | 20.50% | 2.19 | 1.27 |
| node-receiver | 6 | 8 | 8252.20 | enqueue | 2699.16 | 16677.87 | 8745.95 | 52.44% | 47.56% | 5.43 | 1.91 |
