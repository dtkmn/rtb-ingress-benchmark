# Benchmark Summary

- benchmark preset: custom
- delivery mode: http-only
- fairness profile: fixed-envelope
- kafka enabled: false
- kafka acks: 1
- kafka topic: bids (3 partitions, rf 1, min ISR 1)
- kafka producer tuning: linger 10 ms, batch 131072 bytes, request timeout 5000 ms, retry backoff 100 ms, retries 5 when supported
- services: quarkus-receiver quarkus-receiver-native go-receiver rust-receiver python-receiver spring-receiver spring-virtual-receiver node-receiver
- workload: 100 VUs for 30s (warmup 10s)
- receiver budget: 2.0 CPU / 768m
- kafka budget: 2.0 CPU / 1g

- interpretation: `http-only` isolates request parsing, validation, filtering, and response handling without Kafka.

## Capacity Efficiency Ranking

- primary result: `req/s / measured stack avg core` ranks cost-efficient capacity by observed CPU use.
- raw `req/s avg` is a throughput sanity check, not the primary ranking.
- p95/p99 latency and non-2xx/non-204 responses are veto metrics; an efficient result with bad tail latency or errors is not a win.

| rank | service | runs | req/s / measured stack avg core | req/s avg | p95 avg (ms) | req/s / measured stack avg GiB | req/s / receiver CPU limit | req/s / receiver GiB limit |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | rust-receiver | 3 | 38480.29 | 26227.59 | 8.80 | 1744350.55 | 13113.79 | 34970.12 |
| 2 | go-receiver | 3 | 15691.00 | 21528.64 | 10.76 | 609139.65 | 10764.32 | 28704.85 |
| 3 | spring-receiver | 3 | 13438.10 | 21995.64 | 10.32 | 100211.48 | 10997.82 | 29327.52 |
| 4 | spring-virtual-receiver | 3 | 12810.89 | 22261.20 | 10.10 | 120501.84 | 11130.60 | 29681.60 |
| 5 | quarkus-receiver | 3 | 12676.83 | 14104.51 | 16.71 | 60436.90 | 7052.26 | 18806.02 |
| 6 | node-receiver | 3 | 8252.20 | 16677.87 | 14.75 | 67933.49 | 8338.94 | 22237.17 |
| 7 | quarkus-receiver-native | 3 | 7104.61 | 11617.13 | 19.92 | 100615.19 | 5808.56 | 15489.50 |
| 8 | python-receiver | 3 | 6181.43 | 12060.90 | 13.73 | 86312.91 | 6030.45 | 16081.19 |

## Resource Footprint

- `docker stats` CPU percentages are per-core. On a 2 CPU container, `200%` means both CPUs were effectively saturated, so values above `100%` are expected.

| service | receiver cpu avg % | receiver cpu max % | receiver mem avg MiB | receiver mem max MiB | kafka cpu max % | kafka mem max MiB |
|---|---:|---:|---:|---:|---:|---:|
| rust-receiver | 68.17 | 84.28 | 15.40 | 16.39 | n/a | n/a |
| go-receiver | 137.18 | 157.86 | 36.22 | 37.33 | n/a | n/a |
| spring-receiver | 163.72 | 207.78 | 224.74 | 228.40 | n/a | n/a |
| spring-virtual-receiver | 173.81 | 213.06 | 189.18 | 190.80 | n/a | n/a |
| quarkus-receiver | 112.23 | 212.88 | 240.64 | 276.20 | n/a | n/a |
| node-receiver | 202.12 | 216.73 | 251.40 | 267.10 | n/a | n/a |
| quarkus-receiver-native | 163.55 | 192.03 | 118.26 | 131.50 | n/a | n/a |
| python-receiver | 195.11 | 213.27 | 143.09 | 155.30 | n/a | n/a |
