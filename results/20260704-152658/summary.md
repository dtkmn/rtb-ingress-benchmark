# Benchmark Summary

- benchmark preset: custom
- delivery mode: http-only
- fairness profile: fixed-envelope
- git dirty: false
- Quarkus platform: 3.37.1
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
| 1 | rust-receiver | 3 | 47233.18 | 39292.74 | 5.74 | 2642887.62 | 19646.37 | 52390.32 |
| 2 | quarkus-receiver | 3 | 25763.10 | 38896.98 | 5.74 | 156964.25 | 19448.49 | 51862.64 |
| 3 | go-receiver | 3 | 25323.19 | 34415.76 | 6.12 | 1016920.10 | 17207.88 | 45887.68 |
| 4 | spring-receiver | 3 | 20611.13 | 34398.22 | 6.28 | 136475.67 | 17199.11 | 45864.30 |
| 5 | quarkus-receiver-native | 3 | 16924.31 | 30728.39 | 9.95 | 245900.22 | 15364.20 | 40971.19 |
| 6 | spring-virtual-receiver | 3 | 16556.38 | 30933.89 | 6.55 | 138640.65 | 15466.95 | 41245.19 |
| 7 | node-receiver | 3 | 16177.95 | 29747.36 | 7.86 | 146036.74 | 14873.68 | 39663.14 |
| 8 | python-receiver | 3 | 9282.77 | 17866.87 | 8.12 | 112994.94 | 8933.44 | 23822.49 |

## Resource Footprint

- `docker stats` CPU percentages are per-core. On a 2 CPU container, `200%` means both CPUs were effectively saturated, so values above `100%` are expected.

| service | receiver cpu avg % | receiver cpu max % | receiver mem avg MiB | receiver mem max MiB | kafka cpu max % | kafka mem max MiB |
|---|---:|---:|---:|---:|---:|---:|
| rust-receiver | 83.18 | 125.50 | 15.23 | 15.82 | n/a | n/a |
| quarkus-receiver | 150.84 | 187.53 | 258.53 | 306.30 | n/a | n/a |
| go-receiver | 135.98 | 160.84 | 34.66 | 35.54 | n/a | n/a |
| spring-receiver | 166.89 | 185.21 | 258.11 | 259.90 | n/a | n/a |
| quarkus-receiver-native | 181.56 | 195.31 | 127.96 | 143.00 | n/a | n/a |
| spring-virtual-receiver | 186.84 | 214.42 | 228.50 | 230.60 | n/a | n/a |
| node-receiver | 183.87 | 200.32 | 208.59 | 212.00 | n/a | n/a |
| python-receiver | 192.50 | 201.49 | 161.91 | 174.50 | n/a | n/a |
