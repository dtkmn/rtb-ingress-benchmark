'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { buildServer } = require('../src/app');
const {
  loadSettings,
  normalizeDeliveryMode,
  parseKafkaAcks,
  parseNonNegativeInt,
  parsePositiveInt,
  parseWorkerCount,
} = require('../src/config');
const { PublishBackpressureError, PublishUnavailableError } = require('../src/publisher');

function validPayload() {
  return {
    id: 'req-1',
    site: { id: 'site-1', domain: 'example.com' },
    device: { ip: '1.2.3.4', ua: 'test', lmt: 0 },
  };
}

test('accepts requests in http-only mode', async () => {
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'http-only' }),
    publisher: {
      async start() {},
      async stop() {},
      async publish() {
        throw new Error('publish should not be called');
      },
    },
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'POST',
    url: '/bid-request',
    payload: validPayload(),
  });

  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { status: 'accepted' });

  await server.close();
});

test('confirms Kafka delivery when configured', async () => {
  let lastConfirm;
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'confirm', BENCHMARK_KAFKA_ACKS: 'all' }),
    publisher: {
      async start() {},
      async stop() {},
      async publish({ confirm }) {
        lastConfirm = confirm;
      },
    },
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'POST',
    url: '/bid-request',
    payload: validPayload(),
  });

  assert.equal(response.statusCode, 200);
  assert.equal(lastConfirm, true);

  await server.close();
});

test('returns bad request for incomplete payload', async () => {
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'confirm' }),
    publisher: {
      async start() {},
      async stop() {},
      async publish() {},
    },
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'POST',
    url: '/bid-request',
    payload: { id: 'req-1', site: { id: 'site-1' } },
  });

  assert.equal(response.statusCode, 400);
  assert.deepEqual(response.json(), { status: 'bad request' });

  await server.close();
});

test('returns bad request for malformed JSON', async () => {
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'confirm' }),
    publisher: {
      async start() {},
      async stop() {},
      async publish() {},
    },
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'POST',
    url: '/bid-request',
    headers: { 'content-type': 'application/json' },
    payload: '{',
  });

  assert.equal(response.statusCode, 400);
  assert.deepEqual(response.json(), { status: 'bad request' });

  await server.close();
});

test('filters limit-ad-tracking traffic', async () => {
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'confirm' }),
    publisher: {
      async start() {},
      async stop() {},
      async publish() {
        throw new Error('publish should not be called');
      },
    },
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'POST',
    url: '/bid-request',
    payload: {
      id: 'req-1',
      site: { id: 'site-1', domain: 'example.com' },
      device: { ip: '1.2.3.4', lmt: 1 },
    },
  });

  assert.equal(response.statusCode, 204);

  await server.close();
});

test('maps Kafka backpressure to service unavailable', async () => {
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'confirm' }),
    publisher: {
      async start() {},
      async stop() {},
      async publish() {
        throw new PublishBackpressureError(new Error('busy'));
      },
    },
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'POST',
    url: '/bid-request',
    payload: validPayload(),
  });

  assert.equal(response.statusCode, 503);
  assert.deepEqual(response.json(), { status: 'kafka buffer full' });

  await server.close();
});

test('maps unavailable publisher to service unavailable', async () => {
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'confirm' }),
    publisher: {
      async start() {},
      async stop() {},
      async publish() {
        throw new PublishUnavailableError(new Error('down'));
      },
    },
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'POST',
    url: '/bid-request',
    payload: validPayload(),
  });

  assert.equal(response.statusCode, 503);
  assert.deepEqual(response.json(), { status: 'kafka unavailable' });

  await server.close();
});

test('exposes health endpoint', async () => {
  const server = await buildServer({
    settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'http-only' }),
    bootstrapPublisher: false,
  });

  const response = await server.inject({
    method: 'GET',
    url: '/health',
  });

  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { status: 'healthy' });

  await server.close();
});

test('normalizes delivery mode and Kafka settings', () => {
  assert.equal(normalizeDeliveryMode('enqueue'), 'enqueue');
  assert.equal(normalizeDeliveryMode('weird'), 'confirm');
  assert.equal(parseKafkaAcks('all'), -1);
  assert.equal(parseKafkaAcks('none'), 0);
  assert.equal(parseKafkaAcks('weird'), 1);
  assert.equal(parseNonNegativeInt('0', 42, 'BENCHMARK_KAFKA_RETRIES'), 0);
  assert.equal(parseNonNegativeInt('weird', 42, 'BENCHMARK_KAFKA_RETRIES'), 42);
  assert.equal(parsePositiveInt('5000', 42, 'BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS'), 5000);
  assert.equal(parsePositiveInt('weird', 42, 'BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS'), 42);
  assert.equal(parseWorkerCount('2'), 2);
  assert.equal(parseWorkerCount('weird') > 0, true);
});
