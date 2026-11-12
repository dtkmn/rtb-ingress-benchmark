'use strict';

const { Kafka, KafkaJSNonRetriableError, logLevel } = require('kafkajs');
const kafkaVersion = require('kafkajs/package.json').version;
const net = require('node:net');
const tls = require('node:tls');

class PublishUnavailableError extends Error {
  constructor(cause) {
    super('Kafka unavailable');
    this.name = 'PublishUnavailableError';
    this.cause = cause;
  }
}

class PublishBackpressureError extends Error {
  constructor(cause) {
    super('Kafka backpressure');
    this.name = 'PublishBackpressureError';
    this.cause = cause;
  }
}

function createKafkaPublisher(settings, logger = console) {
  // Snapshot the actual SDK arguments; endpoint readback must not reconstruct
  // constructor settings later from a mutable caller object.
  settings = { ...settings, requestedControls: { ...settings.requestedControls } };
  // KafkaJS consults this override in nested loggers as well as its constructor.
  // Controlled mode enforces silence; application failure counters/lifecycle and
  // bounded diagnostics remain independent of SDK request/debug logs.
  const requestedSdkLogLevel = process.env.KAFKAJS_LOG_LEVEL ?? null;
  if (settings.telemetryProfile === 'controlled') process.env.KAFKAJS_LOG_LEVEL = 'NOTHING';
  let stopping = false;
  const sockets = new Set();
  const operations = new Set();
  const kafka = new Kafka({
    clientId: 'node-receiver',
    brokers: settings.kafkaBootstrapServers.split(',').map((broker) => broker.trim()).filter(Boolean),
    connectionTimeout: settings.kafkaRequestTimeoutMs,
    requestTimeout: settings.kafkaRequestTimeoutMs,
    logLevel: settings.telemetryProfile === 'controlled' ? logLevel.NOTHING : logLevel.INFO,
    // KafkaJS retries can reconnect after disconnect. Fence new sockets during
    // teardown, then await existing send outcomes before claiming teardown.
    socketFactory({ host, port, ssl, onConnect }) {
      if (stopping) throw new KafkaJSNonRetriableError('Producer transport is closed');
      const socket = ssl ? tls.connect({ host, port, ...(!net.isIP(host) ? { servername: host } : {}), ...ssl }, onConnect) : net.connect({ host, port }, onConnect);
      socket.setKeepAlive(true, 60000);
      sockets.add(socket);
      socket.once('close', () => sockets.delete(socket));
      return socket;
    },
  });

  const producer = kafka.producer({
    allowAutoTopicCreation: false,
    retry: {
      retries: settings.kafkaRetries,
      initialRetryTime: settings.kafkaRetryBackoffMs,
    },
    ...(settings.isConfirm() ? { maxInFlightRequests: 1 } : {}),
  });
  let available = false;
  producer.on(producer.events.DISCONNECT, () => { available = false; });

  return {
    runtimeSnapshot() {
      const applied = (requested, value, evidence) => ({ status: 'applied', requested, applied: value, effective: null, evidence });
      const request = (name, fallback) => settings.requestedControls?.[name] ?? fallback;
      const unsupported = (name, fallback) => ({ status: 'unsupported', requested: request(name, fallback), applied: null, effective: null, evidence: 'No matching shared control implemented by this KafkaJS adapter' });
      return {
        client: 'KafkaJS', version: kafkaVersion, version_evidence: 'Installed KafkaJS package.json used by this running application', instances: 1, evidence: 'One concrete KafkaJS producer was constructed in this process; no SDK effective configuration getters for these constructor/send arguments',
        controls: {
          acks: applied(request('BENCHMARK_KAFKA_ACKS', settings.kafkaAcks), settings.kafkaAcks, 'Actual per-send acks argument; no SDK effective getter'),
          linger_ms: unsupported('BENCHMARK_KAFKA_LINGER_MS', 10), batch_bytes: unsupported('BENCHMARK_KAFKA_BATCH_BYTES', 131072), producer_pool_size: unsupported('BENCHMARK_KAFKA_PRODUCER_POOL_SIZE', 1),
          retries: applied(request('BENCHMARK_KAFKA_RETRIES', settings.kafkaRetries), settings.kafkaRetries, 'Actual producer retry.retries argument; no SDK effective getter'),
          retry_backoff_ms: applied(request('BENCHMARK_KAFKA_RETRY_BACKOFF_MS', settings.kafkaRetryBackoffMs), settings.kafkaRetryBackoffMs, 'Actual retry.initialRetryTime; KafkaJS exponential/randomized retry remains client-specific'),
          request_timeout_ms: applied(request('BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS', settings.kafkaRequestTimeoutMs), settings.kafkaRequestTimeoutMs, 'Actual Kafka connectionTimeout/requestTimeout and per-send timeout arguments'),
          max_in_flight_requests: applied(null, settings.isConfirm() ? 1 : null, 'Actual producer argument: one for confirm, SDK default for enqueue; no SDK effective getter'),
          idempotent: { status: 'applied', requested: null, applied: false, effective: producer.isIdempotent(), evidence: 'Public producer.isIdempotent() readback; constructor default is false' },
          sdk_log_level: applied(requestedSdkLogLevel, settings.telemetryProfile === 'controlled' ? 'NOTHING' : 'INFO', 'Controlled enforces KAFKAJS_LOG_LEVEL=NOTHING; operational constructor INFO may be overridden by the environment. SDK logger has no public level getter'),
        },
      };
    },
    async start() {
      await producer.connect();
      available = true;
    },
    async stop() {
      available = false;
      stopping = true;
      for (const socket of sockets) socket.destroy();
      await producer.disconnect();
      await Promise.allSettled([...operations]);
    },
    isAvailable() { return available; },
    async publish({ topic, payload, key }) {
      if (stopping) throw new PublishUnavailableError(new Error('Producer transport is closed'));
      const operation = producer.send({
        topic,
        messages: [
          {
            key,
            value: payload,
          },
        ],
        acks: settings.kafkaAcks,
        timeout: settings.kafkaRequestTimeoutMs,
      });
      operations.add(operation);

      try {
        await operation;
      } catch (error) {
        throw classifyKafkaError(error);
      } finally { operations.delete(operation); }
    },
  };
}

function classifyKafkaError(error) {
  const message = String(error?.message || '').toLowerCase();
  if (
    message.includes('timeout') ||
    message.includes('queue') ||
    message.includes('request') ||
    message.includes('not enough replicas')
  ) {
    return new PublishBackpressureError(error);
  }
  return new PublishUnavailableError(error);
}

module.exports = {
  PublishUnavailableError,
  PublishBackpressureError,
  createKafkaPublisher,
};
