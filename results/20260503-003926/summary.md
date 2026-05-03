# Benchmark Summary

- benchmark preset: strict-1
- delivery mode: confirm
- fairness profile: strict-1
- kafka enabled: true
- kafka acks: 1
- kafka topic: bids-strict-1 (1 partitions, rf 1, min ISR 1)
- kafka producer tuning: linger 10 ms, batch 131072 bytes, request timeout 5000 ms, retry backoff 100 ms, retries 0 when supported
- services: quarkus-receiver quarkus-receiver-native go-receiver rust-receiver python-receiver spring-receiver node-receiver
- workload: 100 VUs for 30s (warmup 10s)
- receiver budget: 2.0 CPU / 768m
- kafka budget: 2.0 CPU / 1g

- interpretation: `confirm` ranks combined HTTP + Kafka client behavior by CPU-normalized capacity, not pure framework speed.
- interpretation: use the matched `http-only` delta section below to see how much throughput each service retained once Kafka was in the path.
- interpretation: no compatible matched `http-only` run was found for this summary, so raw req/s is easier to misread.

## Capacity Efficiency Ranking

- primary result: `req/s / measured stack avg core` ranks cost-efficient capacity by observed CPU use.
- raw `req/s avg` is a throughput sanity check, not the primary ranking.
- p95/p99 latency and non-2xx/non-204 responses are veto metrics; an efficient result with bad tail latency or errors is not a win.

| rank | service | runs | req/s / measured stack avg core | req/s avg | p95 avg (ms) | req/s / measured stack avg GiB | req/s / receiver CPU limit | req/s / receiver GiB limit |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | spring-receiver | 3 | 15015.54 | 7306.14 | 18.93 | 9001.27 | 3653.07 | 9741.52 |
| 2 | rust-receiver | 3 | 13937.31 | 6815.80 | 25.42 | 11844.60 | 3407.90 | 9087.74 |
| 3 | quarkus-receiver | 3 | 6486.70 | 6131.49 | 28.11 | 8034.04 | 3065.75 | 8175.32 |
| 4 | python-receiver | 3 | 5960.38 | 6325.54 | 27.02 | 10030.95 | 3162.77 | 8434.05 |
| 5 | go-receiver | 3 | 5774.84 | 5731.93 | 31.86 | 9680.14 | 2865.97 | 7642.58 |
| 6 | quarkus-receiver-native | 3 | 5744.32 | 5385.69 | 30.64 | 8643.90 | 2692.85 | 7180.92 |
| 7 | node-receiver | 3 | 1946.11 | 3348.29 | 51.93 | 4755.97 | 1674.14 | 4464.38 |

## Resource Footprint

- `docker stats` CPU percentages are per-core. On a 2 CPU container, `200%` means both CPUs were effectively saturated, so values above `100%` are expected.

| service | receiver cpu avg % | receiver cpu max % | receiver mem avg MiB | receiver mem max MiB | kafka cpu max % | kafka mem max MiB |
|---|---:|---:|---:|---:|---:|---:|
| spring-receiver | 35.37 | 102.63 | 256.93 | 260.60 | 182.20 | 656.80 |
| rust-receiver | 24.10 | 59.54 | 18.60 | 19.36 | 195.89 | 651.00 |
| quarkus-receiver | 55.36 | 165.96 | 277.16 | 322.50 | 206.14 | 596.20 |
| python-receiver | 89.93 | 119.17 | 71.94 | 83.36 | 162.23 | 655.30 |
| go-receiver | 63.80 | 102.33 | 64.55 | 82.82 | 212.71 | 622.40 |
| quarkus-receiver-native | 64.57 | 97.18 | 109.91 | 131.80 | 198.15 | 609.40 |
| node-receiver | 106.62 | 125.14 | 145.20 | 145.70 | 205.33 | 657.90 |
