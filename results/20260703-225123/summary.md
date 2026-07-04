# Benchmark Summary

- benchmark preset: strict-1
- delivery mode: confirm
- fairness profile: strict-1
- git dirty: false
- Quarkus platform: 3.37.1
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
| 1 | rust-receiver | 3 | 25949.22 | 7282.62 | 17.69 | 13735.71 | 3641.31 | 9710.16 |
| 2 | spring-receiver | 3 | 16441.36 | 7066.73 | 18.32 | 8860.59 | 3533.37 | 9422.31 |
| 3 | quarkus-receiver | 3 | 14680.41 | 6673.32 | 19.46 | 9067.42 | 3336.66 | 8897.76 |
| 4 | go-receiver | 3 | 14204.53 | 6917.08 | 19.73 | 12486.66 | 3458.54 | 9222.77 |
| 5 | quarkus-receiver-native | 3 | 12178.90 | 6254.72 | 20.98 | 11852.76 | 3127.36 | 8339.62 |
| 6 | python-receiver | 3 | 8655.38 | 5834.24 | 21.57 | 9929.69 | 2917.12 | 7778.99 |
| 7 | node-receiver | 3 | 3521.52 | 6358.03 | 21.31 | 9779.47 | 3179.02 | 8477.37 |

## Resource Footprint

- `docker stats` CPU percentages are per-core. On a 2 CPU container, `200%` means both CPUs were effectively saturated, so values above `100%` are expected.

| service | receiver cpu avg % | receiver cpu max % | receiver mem avg MiB | receiver mem max MiB | kafka cpu max % | kafka mem max MiB |
|---|---:|---:|---:|---:|---:|---:|
| rust-receiver | 17.75 | 28.04 | 17.90 | 18.47 | 93.50 | 634.30 |
| spring-receiver | 32.72 | 59.60 | 285.88 | 288.10 | 93.37 | 640.10 |
| quarkus-receiver | 30.64 | 55.43 | 278.09 | 326.00 | 90.60 | 596.30 |
| go-receiver | 36.61 | 55.81 | 61.41 | 72.90 | 175.53 | 611.20 |
| quarkus-receiver-native | 39.57 | 46.31 | 41.56 | 50.18 | 89.94 | 609.50 |
| python-receiver | 57.09 | 75.92 | 72.84 | 85.66 | 93.00 | 634.60 |
| node-receiver | 114.14 | 128.80 | 126.04 | 127.20 | 185.43 | 635.70 |
