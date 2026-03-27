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

  return {
    deliveryMode,
    kafkaBootstrapServers,
    kafkaTopic,
    kafkaAcks,
    kafkaRequestTimeoutMs,
    kafkaRetries,
    kafkaRetryBackoffMs,
    httpServerWorkers,
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
