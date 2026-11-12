'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { request } = require('node:http');
const { resolve } = require('node:path');
const { buildServer } = require('../src/app');
const { loadSettings } = require('../src/config');
const { exactLmt, parseReceiverBody, validHeaders } = require('../src/contract');

const fixtures = JSON.parse(readFileSync(resolve(__dirname, '../../../tests/conformance/corpus.json'))).cases.filter((fixture) => !fixture.scenario);
const settings = (mode) => loadSettings({ BENCHMARK_DELIVERY_MODE: mode, BENCHMARK_KAFKA_ACKS: '1', HTTP_SERVER_WORKERS: '1' });
const rawBytes = (fixture) => Buffer.from(fixture.request.body.data, fixture.request.body.encoding === 'base64' ? 'base64' : 'utf8');
const tick = () => new Promise((done) => setImmediate(done));

function send(port, headers, body, chunked = false) {
  return new Promise((done, reject) => {
    const values = ['Host', `127.0.0.1:${port}`, 'Connection', 'close', ...headers.flatMap(({ name, value }) => [name, value])];
    values.push(chunked ? 'Transfer-Encoding' : 'Content-Length', chunked ? 'chunked' : String(body.length));
    const call = request({ host: '127.0.0.1', port, path: '/bid-request', method: 'POST', headers: values }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => done({ status: response.statusCode, body: Buffer.concat(chunks) }));
    });
    call.on('error', reject);
    if (chunked) { call.write(body.subarray(0, 32768)); call.end(body.subarray(32768)); }
    else call.end(body);
  });
}

test('exact LMT uses mathematical decimal tokens, including huge exponent zero', () => {
  for (const value of ['0', '-0', '0.000e999999999999999999999', '-0e-999999999999999', '0.0']) assert.equal(exactLmt(value), 0);
  for (const value of ['1', '1.0', '10e-1', '0.01e2', '1e+000', '1e-000']) assert.equal(exactLmt(value), 1);
  for (const value of ['0.9999999999999999999999', '1.0000000000000000000001', '-1', '1e999999999999999999999', '0.001', '2']) assert.throws(() => exactLmt(value));
});

test('strict raw/schema checks cover scalar lengths, nulls, type coercion and decoded duplicates', () => {
  for (const fixture of fixtures) {
    const expected = fixture.expectations['http-only'].status;
    const headers = fixture.request.headers.flatMap(({ name, value }) => [name, value]);
    if (expected === 415) { assert.equal(validHeaders(headers), false, fixture.id); continue; }
    assert.equal(validHeaders(headers), true, fixture.id);
    if (expected === 413) continue; // The HTTP reader, not the decoded model, owns the size bound.
    if (expected === 400) assert.throws(() => parseReceiverBody(rawBytes(fixture)), fixture.id);
    else assert.doesNotThrow(() => parseReceiverBody(rawBytes(fixture)), fixture.id);
  }
  const common = '"id":"one","site":{},"device":';
  for (const object of ['{"lmt":null}', '{"lmt":true}', '{"lmt":"0"}', '{"ip":"\\ud800"}', '{"ua":"\\udc00"}']) {
    assert.throws(() => parseReceiverBody(Buffer.from('{' + common + object + '}')));
  }
});

test('all 52 raw corpus cases pass in all three HTTP modes; selected Kafka intent is exact', async () => {
  for (const mode of ['http-only', 'confirm', 'enqueue']) {
    const sent = [];
    let initialized = 0, serializations = 0;
    const options = { settings: settings(mode), logger: false, publisher: {
      async start() { initialized++; }, async stop() {},
      async publish(record) { sent.push({ key: record.key.toString('utf8'), value: JSON.parse(record.payload), topic: record.topic }); },
    } };
    if (mode === 'http-only') options.serialize = () => { serializations++; throw new Error('HTTP-only serialization'); };
    const server = await buildServer(options);
    try {
      await server.listen({ host: '127.0.0.1', port: 0 });
      const port = server.server.address().port;
      for (const fixture of fixtures) {
        const expected = fixture.expectations[mode];
        const before = sent.length;
        const response = await send(port, fixture.request.headers, rawBytes(fixture));
        assert.equal(response.status, expected.status, mode + ':' + fixture.id + ':' + response.body.toString());
        if (expected.status === 200) assert.deepEqual(JSON.parse(response.body), { status: 'accepted' });
        if (expected.status === 204) assert.equal(response.body.length, 0);
        await tick();
        if (expected.record) {
          assert.equal(sent.length, before + 1, fixture.id);
          assert.deepEqual(sent.at(-1), { key: expected.record.key, value: expected.record.value, topic: settings(mode).kafkaTopic }, fixture.id);
        } else assert.equal(sent.length, before, fixture.id);
        assert.equal(server.pipeline.snapshot().preparation_count, 0, fixture.id);
      }
      if (mode === 'http-only') { assert.equal(initialized, 0); assert.equal(serializations, 0); }
      else assert.equal(initialized, 1);
    } finally { await server.close(); }
  }
});

test('unknown-length reader enforces actual bytes after header precedence', async () => {
  const server = await buildServer({ settings: settings('http-only'), logger: false });
  try {
    await server.listen({ host: '127.0.0.1', port: 0 });
    const port = server.server.address().port;
    const body = Buffer.from('{"id":"one","site":{},"device":{}}');
    const sized = (size) => Buffer.concat([body, Buffer.alloc(size - body.length, 32)]);
    assert.equal((await send(port, [{ name: 'Content-Type', value: 'application/json' }], sized(65536), true)).status, 200);
    assert.equal((await send(port, [{ name: 'Content-Type', value: 'application/json' }], sized(65537), true)).status, 413);
    assert.equal((await send(port, [{ name: 'Content-Type', value: 'text/plain' }], sized(65537), true)).status, 415);
    assert.equal(server.pipeline.snapshot().admission_candidates, 0);
  } finally { await server.close(); }
});

test('invalid media precedes preparation exhaustion without consuming a permit', async () => {
  const server = await buildServer({ settings: loadSettings({ BENCHMARK_DELIVERY_MODE: 'http-only', HTTP_SERVER_WORKERS: '1', BENCHMARK_PREPARATION_MAX_REQUESTS: '1' }), logger: false });
  const release = server.pipeline.acquirePreparation();
  try {
    assert.equal((await server.inject({ method: 'POST', url: '/bid-request', headers: { 'content-type': 'text/plain' }, payload: '{}' })).statusCode, 415);
    assert.equal(server.pipeline.snapshot().preparation_rejected, 0);
    assert.equal(server.pipeline.snapshot().preparation_count, 1);
  } finally { release(); await server.close(); }
});
