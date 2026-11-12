'use strict';
const cluster = require('node:cluster');
const v8 = require('node:v8');
const { readCgroup } = require('./cgroup');
const kafkaVersion = require('kafkajs/package.json').version;

function runtimeSnapshot(server, settings, publisher, logController) {
  const snapshot = server.pipeline.snapshot();
  const requested = (name, fallback) => settings.requestedControls?.[name] ?? fallback;
  const active = settings.usesKafka();
  let producer = active ? publisher?.runtimeSnapshot?.() : null;
  if (!producer) {
    producer = { client: 'KafkaJS', version: kafkaVersion, version_evidence: 'Installed KafkaJS package.json used by this running application', instances: 0, evidence: active ? 'No concrete KafkaJS producer constructed; injected adapters are not SDK introspection' : 'HTTP-only constructs no Kafka producer', controls: {} };
    for (const [key, env, fallback] of [
      ['acks', 'BENCHMARK_KAFKA_ACKS', settings.kafkaAcks], ['linger_ms', 'BENCHMARK_KAFKA_LINGER_MS', 10],
      ['batch_bytes', 'BENCHMARK_KAFKA_BATCH_BYTES', 131072], ['retries', 'BENCHMARK_KAFKA_RETRIES', settings.kafkaRetries],
      ['retry_backoff_ms', 'BENCHMARK_KAFKA_RETRY_BACKOFF_MS', settings.kafkaRetryBackoffMs],
      ['request_timeout_ms', 'BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS', settings.kafkaRequestTimeoutMs], ['producer_pool_size', 'BENCHMARK_KAFKA_PRODUCER_POOL_SIZE', 1],
    ]) producer.controls[key] = { status: 'not_applicable', requested: requested(env, fallback), applied: null, effective: null, evidence: 'No concrete KafkaJS producer in this process' };
  }
  if (server.pipeline.conformance) {
    producer.conformance_transport = true;
    producer.evidence = 'Token-gated receiver conformance transport; zero actual KafkaJS producers. Linked SDK identity is not SDK construction or broker/durability proof.';
  }
  return {
    schema_version: 1, lane: 'node', scope: 'worker_process', delivery_mode: settings.deliveryMode,
    telemetry: { profile: settings.telemetryProfile, access_log: !logController.isLogDisabled({}) && (server.log.isLevelEnabled?.('info') ?? false), tracing: false, trace_export: false, body_size_metrics: false, request_latency_histogram: false,
      evidence: 'Live Fastify LogController.isLogDisabled() and logger enablement determine access logs. No tracing/export/body-size/latency instrumentation installed; F2 aggregate counters and lifecycle retained. Controlled unexpected error diagnostics bounded to 3 messages of at most 512 characters per minute.' },
    runtime: {
      pid: process.pid, kind: 'Node.js', version: process.version,
      http_parallelism: { control: 'HTTP_SERVER_WORKERS', requested: requested('HTTP_SERVER_WORKERS', settings.httpServerWorkers), effective: 1,
        observed_serving_processes: 1, cluster_worker: cluster.isWorker, cluster_worker_id: cluster.worker?.id ?? null,
        evidence: 'This endpoint observes one serving process; configured whole-container worker count is not a process census. Verify all worker PIDs separately.' },
      heap: { max_bytes: v8.getHeapStatistics().heap_size_limit, gc: 'V8', evidence: 'Public V8 heap_size_limit readback; heap maximum is not total process RSS or a container hard limit' },
      cgroup: readCgroup(),
    },
    producer,
    pipeline: { max_records: settings.pipelineRecordLimit, max_bytes: settings.pipelineByteLimit, max_preparation_requests: settings.preparationLimit,
      http_outcome_timeout_ms: settings.httpOutcomeTimeoutMs, drain_timeout_ms: settings.drainTimeoutMs, configured_container_workers: settings.httpServerWorkers,
      evidence: 'Actual immutable limits used by this worker pipeline; static worker shares are not an observed whole-container process count', preparation_count: snapshot.preparation_count },
  };
}

module.exports = { runtimeSnapshot };
