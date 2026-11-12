'use strict';

const { availableParallelism } = require('node:os');

const DELIVERY_MODE_CONFIRM = 'confirm';
const DELIVERY_MODE_ENQUEUE = 'enqueue';
const DELIVERY_MODE_HTTP_ONLY = 'http-only';

function normalizeDeliveryMode(raw) {
  const candidate = String(raw ?? DELIVERY_MODE_CONFIRM).trim().toLowerCase() || DELIVERY_MODE_CONFIRM;
  if (
    candidate === DELIVERY_MODE_CONFIRM ||
    candidate === DELIVERY_MODE_ENQUEUE ||
    candidate === DELIVERY_MODE_HTTP_ONLY
  ) {
    return candidate;
  }

  console.warn(
    'Unknown BENCHMARK_DELIVERY_MODE=%j; defaulting to %j',
    raw,
    DELIVERY_MODE_CONFIRM
  );
  return DELIVERY_MODE_CONFIRM;
}

function parseKafkaAcks(raw) {
  const candidate = String(raw ?? '1').trim().toLowerCase() || '1';
  switch (candidate) {
    case '0':
    case 'none':
      return 0;
    case '-1':
    case 'all':
      return -1;
    case '1':
    case 'leader':
      return 1;
    default:
      console.warn(
        'Unknown BENCHMARK_KAFKA_ACKS=%j; defaulting to leader acknowledgements',
        raw
      );
      return 1;
  }
}

function parsePositiveInt(raw, fallback, envName) {
  const candidate = String(raw ?? '').trim();
  if (candidate === '') {
    return fallback;
  }

  const parsed = Number.parseInt(candidate, 10);
  if (Number.isInteger(parsed) && parsed > 0) {
    return parsed;
  }

  console.warn('Ignoring invalid %s=%j; defaulting to %d', envName, raw, fallback);
  return fallback;
}

function parseNonNegativeInt(raw, fallback, envName) {
  const candidate = String(raw ?? '').trim();
  if (candidate === '') {
    return fallback;
  }

  const parsed = Number.parseInt(candidate, 10);
  if (Number.isInteger(parsed) && parsed >= 0) {
    return parsed;
  }

  console.warn('Ignoring invalid %s=%j; defaulting to %d', envName, raw, fallback);
  return fallback;
}

function parseWorkerCount(raw) {
  if (raw == null || String(raw).trim() === '') {
    return availableParallelism();
  }

  const parsed = Number.parseInt(String(raw).trim(), 10);
  if (Number.isInteger(parsed) && parsed > 0) {
    return parsed;
  }

  console.warn('Ignoring invalid HTTP_SERVER_WORKERS=%j', raw);
  return availableParallelism();
}

function loadSettings(env = process.env) {
  const telemetryProfile = String(env.BENCHMARK_TELEMETRY_PROFILE ?? 'controlled').trim().toLowerCase() || 'controlled';
  if (!['controlled', 'operational'].includes(telemetryProfile)) throw new Error('BENCHMARK_TELEMETRY_PROFILE must be controlled or operational');
  const deliveryMode = normalizeDeliveryMode(env.BENCHMARK_DELIVERY_MODE);
  const kafkaBootstrapServers = String(env.KAFKA_BOOTSTRAP_SERVERS || 'localhost:9092');
  const kafkaTopic = String(env.BENCHMARK_KAFKA_TOPIC || 'bids');
  const kafkaAcks = parseKafkaAcks(env.BENCHMARK_KAFKA_ACKS);
  const kafkaRequestTimeoutMs = parsePositiveInt(
    env.BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS,
    5000,
    'BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS'
  );
  const kafkaRetries = parseNonNegativeInt(
    env.BENCHMARK_KAFKA_RETRIES,
    5,
    'BENCHMARK_KAFKA_RETRIES'
  );
  const kafkaRetryBackoffMs = parseNonNegativeInt(
    env.BENCHMARK_KAFKA_RETRY_BACKOFF_MS,
    100,
    'BENCHMARK_KAFKA_RETRY_BACKOFF_MS'
  );
  const httpServerWorkers = parseWorkerCount(env.HTTP_SERVER_WORKERS);
  const conformanceEnabled = env.BENCHMARK_CONFORMANCE === '1';
  const conformanceToken = String(env.BENCHMARK_CONFORMANCE_TOKEN ?? '');
  if (conformanceEnabled && (!conformanceToken.trim() || httpServerWorkers !== 1)) {
    throw new Error('Conformance requires a nonempty token and exactly one serving worker');
  }
  if (deliveryMode === DELIVERY_MODE_CONFIRM && kafkaAcks === 0) throw new Error('confirm with acknowledgements=0 is unsupported');
  const bound = (name, fallback) => {
    if (env[name] == null || env[name] === '') return fallback;
    if (!/^\d+$/.test(String(env[name])) || !Number.isSafeInteger(Number(env[name])) || Number(env[name]) <= 0) throw new Error(`${name} must be a positive integer`);
    return Number(env[name]);
  };
  const pipelineMaxRecords = bound('BENCHMARK_PIPELINE_MAX_RECORDS', 1024);
  const pipelineMaxBytes = bound('BENCHMARK_PIPELINE_MAX_BYTES', 16 * 1024 * 1024);
  const preparationMaxRequests = bound('BENCHMARK_PREPARATION_MAX_REQUESTS', 128);
  const pipelineRecordLimit = Math.floor(pipelineMaxRecords / httpServerWorkers);
  const pipelineByteLimit = Math.floor(pipelineMaxBytes / httpServerWorkers);
  const preparationLimit = Math.floor(preparationMaxRequests / httpServerWorkers);
  if (Math.min(pipelineRecordLimit, pipelineByteLimit, preparationLimit) < 1) throw new Error('Worker count exceeds the container pipeline/preparation budget');

  return {
    telemetryProfile,
    conformanceEnabled, conformanceToken,
    requestedControls: Object.fromEntries(['BENCHMARK_KAFKA_ACKS', 'BENCHMARK_KAFKA_LINGER_MS', 'BENCHMARK_KAFKA_BATCH_BYTES', 'BENCHMARK_KAFKA_RETRIES', 'BENCHMARK_KAFKA_RETRY_BACKOFF_MS', 'BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS', 'BENCHMARK_KAFKA_PRODUCER_POOL_SIZE', 'HTTP_SERVER_WORKERS'].map((name) => [name, env[name] ?? null])),
    deliveryMode,
    kafkaBootstrapServers,
    kafkaTopic,
    kafkaAcks,
    kafkaRequestTimeoutMs,
    kafkaRetries,
    kafkaRetryBackoffMs,
    httpServerWorkers,
    pipelineMaxRecords, pipelineMaxBytes, preparationMaxRequests,
    pipelineRecordLimit, pipelineByteLimit, preparationLimit,
    httpOutcomeTimeoutMs: bound('BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS', 30000),
    drainTimeoutMs: bound('BENCHMARK_DRAIN_TIMEOUT_MS', 30000),
    usesKafka() {
      return this.deliveryMode !== DELIVERY_MODE_HTTP_ONLY;
    },
    isConfirm() {
      return this.deliveryMode === DELIVERY_MODE_CONFIRM;
    },
    isHttpOnly() {
      return this.deliveryMode === DELIVERY_MODE_HTTP_ONLY;
    },
  };
}

module.exports = {
  DELIVERY_MODE_CONFIRM,
  DELIVERY_MODE_ENQUEUE,
  DELIVERY_MODE_HTTP_ONLY,
  loadSettings,
  normalizeDeliveryMode,
  parseKafkaAcks,
  parseNonNegativeInt,
  parsePositiveInt,
  parseWorkerCount,
};
