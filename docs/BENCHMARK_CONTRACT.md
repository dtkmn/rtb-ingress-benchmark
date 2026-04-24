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
- `BENCHMARK_KAFKA_PRODUCER_POOL_SIZE=<n>` when the lane supports explicit producer slots

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

## Fairness Profiles

Do not confuse "same environment variables" with fairness. The same values can create different runtime topologies:

- Python and Node use `HTTP_SERVER_WORKERS` as OS worker processes, and each process creates its own Kafka producer.
- Rust uses `HTTP_SERVER_WORKERS` as Actix worker threads inside one process and uses `BENCHMARK_KAFKA_PRODUCER_POOL_SIZE` for producer slots.
- Go uses `GOMAXPROCS` for scheduler parallelism and currently has one process-level Kafka writer.
- Quarkus and Spring lanes use one process with configurable Java `KafkaProducer` pools.
- Spring virtual threads intentionally do not map cleanly to a fixed worker-count setting.

Use one of these profiles when interpreting results:

`strict-1`

- One HTTP execution lane where the runtime allows it.
- One logical Kafka producer lane.
- Best current profile for topology-normalized comparisons.
- Example:

```bash
HTTP_SERVER_WORKERS=1 \
GOMAXPROCS=1 \
QUARKUS_HTTP_IO_THREADS=1 \
BENCHMARK_KAFKA_PRODUCER_POOL_SIZE=1 \
scripts/run-benchmark-matrix.sh
```

`fixed-envelope`

- Same container CPU and memory budget for every service.
- Runtime-specific concurrency knobs default from `BENCHMARK_RECEIVER_CPUS`.
- `scripts/run-benchmark-matrix.sh` defaults `BENCHMARK_KAFKA_PRODUCER_POOL_SIZE=2` for lanes that support explicit producer slots.
- This is the default matrix behavior.
- Good for "what performs best inside this resource envelope" comparisons, but not a pure language or framework comparison.

`idiomatic`

- Each lane uses the most natural production-ish shape inside the same CPU and memory budget.
- This can be useful, but it must be labeled clearly because worker and producer topology may differ materially.

Do not publish a whole-matrix `strict-2` profile until every lane has an explicit and documented way to expose two logical Kafka producer lanes or a defensible equivalent. Today, the matrix script defaults Rust and the Java lanes to `BENCHMARK_KAFKA_PRODUCER_POOL_SIZE=2`, Python and Node get one producer per worker process, and Go still uses one process-level writer. That is a valid fixed-envelope experiment, not a strict whole-matrix producer-normalized result.

## Kafka Publish Mechanics By Lane

Every accepted request still becomes one Kafka record. No lane performs manual app-level aggregation of multiple HTTP requests into one Kafka batch. Any batching happens inside the Kafka client library, and those client libraries do not behave identically.

| lane | client library | app payload before send | enqueue behavior | `confirm` waits for | client-side batching and queueing notes | notable caveat |
|---|---|---|---|---|---|---|
| `quarkus-receiver`, `quarkus-receiver-native` | Java `KafkaProducer` pool | pre-serialized `byte[]` | selected producer calls `send(record, callback)` and returns immediately in non-confirm modes | Java producer callback completion | Uses Java client `linger.ms`, `batch.size`, retries, `delivery.timeout.ms`, and `BENCHMARK_KAFKA_PRODUCER_POOL_SIZE` | Same producer code in both Quarkus lanes, but different runtime packaging does not make this a pure framework-only comparison once Kafka is in the path |
| `spring-receiver` | Java `KafkaProducer` pool | pre-serialized `byte[]` | selected producer calls `send(record)` inside a Reactor `Mono` in enqueue mode | Java producer callback bridged into `Mono` completion | Uses the same Java client batching knobs and producer-pool knob as Quarkus | Producer client is similar to Quarkus, but app/runtime integration is different and payload serialization happens before send |
| `spring-virtual-receiver` | Java `KafkaProducer` pool | pre-serialized `byte[]` | selected producer calls `send(record)` and returns completed future in enqueue mode | Java producer callback bridged into `CompletableFuture` completion | Uses the same Java client batching knobs and producer-pool knob as Quarkus and Spring WebFlux | Virtual threads change the HTTP/runtime side, not the underlying Kafka client semantics |
| `go-receiver` | `segmentio/kafka-go` `Writer` | pre-serialized `[]byte` | `Writer.Async` is enabled only in enqueue mode | `WriteMessages(...)` return from the writer in confirm mode | Writer batches with `BatchTimeout`, `BatchBytes`, and `LeastBytes` balancing | `kafka-go` writer batching and retry behavior are not a one-to-one match with the Java or librdkafka clients |
| `rust-receiver` | `librdkafka` `FutureProducer` | JSON `String` | `send_result(record)` in enqueue mode | `FutureProducer.send(record, queue_timeout)` future completion in confirm mode | Uses `linger.ms`, `batch.size`, retries, internal producer queueing, and explicit queue-timeout retry behavior | Local enqueue retry semantics come from librdkafka and are materially different from Java, `kafka-go`, and `aiokafka` |
| `python-receiver` | `aiokafka` `AIOKafkaProducer` | pre-serialized `bytes` | `await producer.send(...)` to enqueue and obtain a delivery future | `await delivery_future` in confirm mode | Uses `linger_ms` and `max_batch_size`; local scheduling can wait up to `request_timeout_ms` | `aiokafka` exposes retry backoff but not the same fixed retry-count control as Java, Go, or Rust |
| `node-receiver` | KafkaJS producer | `Buffer` payload | `producer.send({ messages: [...] })` promise is fired and not awaited after the HTTP response in enqueue mode | `await producer.send(...)` promise in confirm mode | Uses KafkaJS internal buffering; this repo does not have a shared `linger` or `batch.size` equivalent for this lane | KafkaJS confirm mode also forces `maxInFlightRequests=1`, and its batching model is not comparable one-for-one with the other clients |

Read this table before inferring language quality from `confirm` mode. Once Kafka confirmation stays in the request path, you are benchmarking runtime plus Kafka client behavior plus broker interaction, not just HTTP/framework cost.

## HTTP Execution Model By Lane

These services share the same HTTP contract, but they do not share the same execution model. Some handlers return reactive types, some block the request path directly, some default to multiple OS processes, and some rely on event-loop or thread-pool scheduling inside one process. If you skip this table, you will keep telling yourself fake stories about what `confirm` mode is measuring.

| lane | HTTP/runtime model | default service topology in this repo | Kafka publish shape in request path | what the request is actually waiting on in `confirm` | why it can behave differently |
|---|---|---|---|---|---|
| `python-receiver` | FastAPI + Uvicorn asyncio handler (`async def`) | `uvicorn --workers ${HTTP_SERVER_WORKERS}` means multiple OS processes; each worker runs app startup and creates its own `AIOKafkaProducer` | `await producer.send(...)` to enqueue, then `await delivery_future` | asyncio task suspension until `aiokafka` delivery future resolves | `HTTP_SERVER_WORKERS=2` is effectively two Python processes and two producer instances, not two threads in one process |
| `node-receiver` | Fastify async handler on the Node event loop | `HTTP_SERVER_WORKERS>1` uses Node cluster, so each worker is a separate OS process with its own KafkaJS producer | `await producer.send(...)` in `confirm`; in `enqueue` it fires the promise and returns before completion | event-loop task waits on KafkaJS `send()` promise | confirm mode also forces `maxInFlightRequests=1`, so the event-loop model is only part of the story |
| `rust-receiver` | Actix Web async handler on Tokio | one process with `HttpServer::workers(HTTP_SERVER_WORKERS)` Actix worker threads; shared `FutureProducer` pool inside process | `producer.send(...).await` in `confirm`; `send_result(...)` in `enqueue` | async task waits on librdkafka delivery future and any local queue-timeout wait | this is async, but it is still one process with shared producer state unless you explicitly scale replicas or producer pool size |
| `go-receiver` | Gin handler written in straightforward blocking style | one process, `GOMAXPROCS` limits scheduler parallelism; no separate worker-process model | handler calls `KafkaWriter.WriteMessages(...)` directly | the handler itself blocks until `kafka-go` returns from `WriteMessages(...)` | same contract does not mean same async shape; this lane currently pays Kafka wait directly on the handler path |
| `quarkus-receiver`, `quarkus-receiver-native` | RESTEasy Reactive endpoint returning `Uni<Response>` | one process with Quarkus I/O/event-loop parallelism controlled by `QUARKUS_HTTP_IO_THREADS`; configurable application-scoped Java producer pool | selected producer `send(record, callback)` bridged into `CompletionStage`, then into `Uni` | reactive pipeline waits for Java producer callback completion | reactive HTTP does not eliminate Kafka wait; it just changes how that waiting is represented and scheduled |
| `spring-receiver` | Spring WebFlux controller returning `Mono<ResponseEntity<?>>` | one process; `HTTP_SERVER_WORKERS` maps to Reactor Netty `ioWorkerCount`; configurable singleton-managed Java producer pool | selected producer `send(record, callback)` bridged into `Mono.create(...)` | Reactor chain waits for Java producer callback completion | this is non-blocking at the HTTP layer, but producer completion still decides request latency |
| `spring-virtual-receiver` | Spring MVC controller on virtual threads | one process; virtual threads enabled, so this lane relies on container CPU budget rather than an explicit server worker-count knob; configurable Java producer pool | controller calls publisher and `join()`s the returned `CompletableFuture` in `confirm` | the virtual-thread request blocks waiting for producer completion | virtual threads make blocking cheaper than platform threads, but they do not make the Kafka confirm wait disappear |

The practical takeaway is simple:

- `async`, `reactive`, `virtual-thread`, and `blocking` are not interchangeable labels.
- A higher `confirm` score can come from better producer/runtime interaction even when `http-only` is worse.
- A lower `confirm` score does not automatically mean the language is slower; it can mean that this lane parks more expensively, exposes callback overhead, or keeps less useful work in flight.

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
- Label the fairness profile (`strict-1`, `fixed-envelope`, or `idiomatic`) before publishing or comparing rankings.
- Do not call a `confirm` result a language-speed result unless you have also shown compatible `http-only` and producer-normalized evidence.
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
