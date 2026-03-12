# Benchmark Contract

This repository is a benchmark harness, not a permanent leaderboard.

Any result published from this repo must include:

- benchmark date
- git commit SHA
- hardware and OS
- container runtime and version
- benchmark mode
- Kafka acknowledgement setting
- load generator settings

## Scope

The receiver comparison is only valid when each implementation does the same work:

- parse the same JSON payload
- apply the same validation rules
- apply the same filtering rules
- publish to the same Kafka topic
- return the same HTTP semantics

As of March 9, 2026, the receiver contract is:

- `400` for malformed or incomplete requests
- `204` for intentionally filtered requests
- `200` for accepted requests

## Benchmark Modes

Use one mode per run and record it with the results.

`confirm`

- Default mode for this repo.
- The HTTP `200` is returned only after the service receives the Kafka delivery outcome from the client library.
- Default Kafka acknowledgement setting is leader ack (`BENCHMARK_KAFKA_ACKS=1`).
- Use this for cross-language comparisons you intend to defend.

`enqueue`

- The HTTP `200` is returned after the request is accepted into the local producer pipeline.
- Recommended Kafka acknowledgement setting is `BENCHMARK_KAFKA_ACKS=0`.
- The matrix runner defaults `BENCHMARK_KAFKA_ACKS` to `0` in this mode unless you override it explicitly.
- Use this only when you explicitly want a fire-and-forget ingress comparison.

`http-only`

- The HTTP `200` is returned after request parsing, validation, and filtering.
- Kafka is not started and no producer is initialized.
- Use this to separate framework and JSON handling cost from Kafka client cost.

## Supported Knobs

Receiver services read these environment variables:

- `BENCHMARK_DELIVERY_MODE=confirm|enqueue|http-only`
- `BENCHMARK_KAFKA_TOPIC=<name>`
- `BENCHMARK_KAFKA_ACKS=0|1|all`
- `BENCHMARK_KAFKA_LINGER_MS=<n>`
- `BENCHMARK_KAFKA_BATCH_BYTES=<n>`
- `BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS=<n>`
- `BENCHMARK_KAFKA_RETRY_BACKOFF_MS=<n>`
- `BENCHMARK_KAFKA_RETRIES=<n>` when the client library supports an explicit retry count

Worker-style runtimes support:

- `HTTP_SERVER_WORKERS=<n>`

Other runtimes use their native concurrency knobs:

- `GOMAXPROCS=<n>` for Go
- `QUARKUS_HTTP_IO_THREADS=<n>` for Quarkus JVM and native

`HTTP_SERVER_WORKERS` is not a universal “CPU thread count”. Depending on the runtime, it can mean worker processes, worker threads, or Reactor Netty I/O workers. `BENCHMARK_RECEIVER_CPUS` is the container CPU budget; the concurrency knobs above control how much parallel work the HTTP stack tries to keep in flight inside that budget.

The load harness supports:

- `BASE_URL`
- `REPEATS`
- `BUILD_IMAGES`
- `VUS`
- `DURATION`
- `RATE`
- `WARMUP_DURATION`
- `LMT_PERCENT`
- `BLOCKED_IP_PERCENT`
- `BENCHMARK_RECEIVER_CPUS`
- `BENCHMARK_RECEIVER_MEMORY`
- `BENCHMARK_KAFKA_CPUS`
- `BENCHMARK_KAFKA_MEMORY`
- `BENCHMARK_RECEIVER_CPUSET`
- `BENCHMARK_KAFKA_CPUSET`

When these concurrency knobs are not set explicitly, the matrix runner derives a default parallelism from `BENCHMARK_RECEIVER_CPUS` and exports:

- `HTTP_SERVER_WORKERS=ceil(BENCHMARK_RECEIVER_CPUS)`
- `GOMAXPROCS=ceil(BENCHMARK_RECEIVER_CPUS)`
- `QUARKUS_HTTP_IO_THREADS=ceil(BENCHMARK_RECEIVER_CPUS)`

Spring WebFlux maps `HTTP_SERVER_WORKERS` to Reactor Netty’s `reactor.netty.ioWorkerCount` so its event-loop parallelism stays explicit in the matrix.

`spring-virtual-receiver` runs Spring MVC with `spring.threads.virtual.enabled=true`. Spring Boot notes that thread-pool tuning properties do not apply once virtual threads are enabled, so this lane relies on the container CPU limit rather than a service-level worker-count knob.

Kafka producer tuning should be kept aligned across compared services where the client library allows it. This repo now treats these as the baseline producer knobs:

- `BENCHMARK_KAFKA_TOPIC`
- `BENCHMARK_KAFKA_LINGER_MS`
- `BENCHMARK_KAFKA_BATCH_BYTES`
- `BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS`
- `BENCHMARK_KAFKA_RETRY_BACKOFF_MS`
- `BENCHMARK_KAFKA_RETRIES`

The Java, Go, Rust, and Spring lanes support explicit retry count and retry backoff. `aiokafka` exposes retry backoff but not a fixed retry-count knob, so the Python lane still uses `BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS` as its retry budget. KafkaJS does not expose the same cross-request batching controls as the Java, Go, Rust, and aiokafka clients, so the Node lane applies the shared request timeout and retry tuning but can only approximate the rest.

The local benchmark broker also supports these topic and broker knobs:

- `BENCHMARK_KAFKA_TOPIC_PARTITIONS=<n>`
- `BENCHMARK_KAFKA_TOPIC_REPLICATION_FACTOR=<n>`
- `BENCHMARK_KAFKA_TOPIC_MIN_ISR=<n>`
- `BENCHMARK_KAFKA_TOPIC_RETENTION_MS=<n>`
- `BENCHMARK_KAFKA_TOPIC_MAX_MESSAGE_BYTES=<n>`
- `BENCHMARK_KAFKA_BROKER_NUM_NETWORK_THREADS=<n>`
- `BENCHMARK_KAFKA_BROKER_NUM_IO_THREADS=<n>`
- `BENCHMARK_KAFKA_SOCKET_REQUEST_MAX_BYTES=<n>`

The default local Docker stack is still a single-broker Kafka cluster. That makes it easier to benchmark consistently, but it also means replication-factor settings above `1` only make sense in a different deployment topology.

End-to-end sinker runs also support:

- `SINKER_DLQ_ENABLED=true|false`

## Execution Rules

- Compare only one benchmark mode at a time.
- Keep Kafka topology, topic configuration, and downstream consumers constant across compared services.
- Run at least one warmup and at least three measured runs.
- Keep the receiver and Kafka resource budgets fixed across compared services.
- For sinker or end-to-end runs, record whether DLQ was enabled.
- Record p50, p95, p99, throughput, non-2xx/non-204 responses, and resource usage.
- Do not compare results across different hardware without saying so explicitly.
- Do not leave historical winners in docs without the date and the run metadata.

## Default Workflow

Build and run the matrix with the conservative defaults:

```bash
scripts/run-benchmark-matrix.sh
```

The default local resource budget is:

- receiver: `2.0` CPUs and `768m`
- Kafka: `2.0` CPUs and `1g`

Run fire-and-forget ingress mode explicitly:

```bash
BENCHMARK_DELIVERY_MODE=enqueue BENCHMARK_KAFKA_ACKS=0 scripts/run-benchmark-matrix.sh
```

Run HTTP-only mode explicitly:

```bash
BENCHMARK_DELIVERY_MODE=http-only scripts/run-benchmark-matrix.sh
```

Run a single target manually with k6:

```bash
BASE_URL=http://localhost:8072 VUS=200 DURATION=45s k6 run k6/load-test.js
```

Results generated by the runner are written under `results/<timestamp>/` and include:

- per-run k6 summaries
- per-run Docker stats for the receiver and Kafka when Kafka is part of the mode
- `runs.csv`
- `summary.csv`
- `summary.md`
- `summary.json`
- `mode-comparison.csv` when the collator finds a compatible run in the opposite delivery mode

The collated summary includes raw throughput plus normalized views such as:

- `req/s / receiver CPU limit`
- `req/s / receiver GiB limit`
- `req/s / measured stack avg core`
- `req/s / measured stack avg GiB`
- estimated Kafka-added latency when a matching `http-only` or Kafka-enabled comparison run exists
