'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');
const { spawn } = require('node:child_process');
const { resolve } = require('node:path');
const { OwnedPipeline } = require('../src/pipeline');
const { loadSettings } = require('../src/config');
const { buildServer } = require('../src/app');

const settings = (extra = {}) => loadSettings({ HTTP_SERVER_WORKERS: '1', BENCHMARK_DELIVERY_MODE: 'enqueue', ...extra });
const bid = { id: 'one', site: { id: 'site' }, device: { lmt: 0 } };
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const tick = () => new Promise((resolve) => setImmediate(resolve));
const request = (server, payload = bid) => server.inject({ method: 'POST', url: '/bid-request', payload });
const counters = (state) => {
  assert.equal(state.admission_candidates, state.admitted + state.admission_rejected + state.admission_undecided);
  assert.equal(state.admitted, state.client_succeeded + state.client_failed + state.abandoned_unknown + state.outstanding);
};

test('enqueue commits before client outcome, holds slots/bytes and rejects full pipeline', async () => {
  const client = deferred(), started = deferred();
  const server = await buildServer({ settings: settings({ BENCHMARK_PIPELINE_MAX_RECORDS: '1' }), logger: false,
    publisher: { start: async () => {}, stop: async () => {}, publish: () => { started.resolve(); return client.promise; } } });
  try {
    assert.equal((await request(server)).statusCode, 200);
    await started.promise;
    const pending = server.pipeline.snapshot();
    assert.equal(pending.outstanding, 1); assert.ok(pending.outstanding_bytes > 0); assert.equal(pending.preparation_count, 0);
    assert.equal((await request(server, { ...bid, id: 'two' })).statusCode, 503);
    counters(server.pipeline.snapshot());
    client.resolve(); await tick();
    assert.equal(server.pipeline.snapshot().client_succeeded, 1);
    assert.equal(server.pipeline.snapshot().outstanding_bytes, 0);
    assert.equal((await request(server, { ...bid, id: 'three' })).statusCode, 200);
  } finally { client.resolve(); await server.close(); }
});

test('unavailable producer and serialization failure reject before receipt', async () => {
  let calls = 0;
  for (const serializationFailure of [false, true]) {
    const server = await buildServer({ settings: settings(), logger: false,
      ...(serializationFailure ? { serialize: () => { throw new Error('serialize'); } } : {}),
      publisher: { start: async () => {}, stop: async () => {}, isAvailable: () => false, publish: async () => { calls++; } } });
    try {
      assert.equal((await request(server)).statusCode, serializationFailure ? 500 : 503);
      const state = server.pipeline.snapshot();
      assert.equal(state.admitted, 0); assert.equal(state.admission_rejected, 1); counters(state);
    } finally { await server.close(); }
  }
  assert.equal(calls, 0);
});

test('an immediate failed SDK promise is a client failure after owned enqueue receipt', async () => {
  const server = await buildServer({ settings: settings(), logger: false,
    publisher: { start: async () => {}, stop: async () => {}, publish: () => Promise.reject(new Error('SDK failed')) } });
  try {
    assert.equal((await request(server)).statusCode, 200);
    await tick();
    const state = server.pipeline.snapshot();
    assert.equal(state.admitted, 1); assert.equal(state.client_failed, 1); assert.equal(state.admission_rejected, 0); counters(state);
  } finally { await server.close(); }
});

test('confirm deadline retains credits; late success settles once without a fake client failure', async () => {
  const client = deferred(), started = deferred();
  const server = await buildServer({ settings: settings({ BENCHMARK_DELIVERY_MODE: 'confirm', BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS: '20', BENCHMARK_PIPELINE_MAX_RECORDS: '1' }), logger: false,
    publisher: { start: async () => {}, stop: async () => {}, publish: () => { started.resolve(); return client.promise; } } });
  try {
    const response = request(server); await started.promise;
    assert.equal((await response).statusCode, 503);
    const pending = server.pipeline.snapshot();
    assert.equal(pending.outstanding, 1); assert.equal(pending.client_failed, 0); assert.equal(pending.deadline_observations, 1);
    assert.equal((await request(server)).statusCode, 503);
    client.resolve(); await tick();
    const state = server.pipeline.snapshot();
    assert.equal(state.client_succeeded, 1); assert.equal(state.client_failed, 0); assert.equal(state.outstanding_bytes, 0); counters(state);
    assert.equal(server.pipeline.settle(1, 'client_failed', new Error('duplicate callback')), false);
    assert.equal(server.pipeline.snapshot().client_failed, 0);
  } finally { client.resolve(); await server.close(); }
});

test('byte limit and concurrent final-slot admission are atomic; source buffers are copied', async () => {
  const held = deferred(); let observed;
  const pipeline = new OwnedPipeline({ settings: settings({ BENCHMARK_PIPELINE_MAX_RECORDS: '1', BENCHMARK_PIPELINE_MAX_BYTES: '4' }), available: () => true,
    dispatch: (record) => { observed = record; return held.promise; }, teardown: async () => {} });
  const key = Buffer.from('k'), payload = Buffer.from('abc');
  const contenders = [pipeline.beginCandidate(), pipeline.beginCandidate()];
  const barrier = deferred();
  const results = contenders.map((candidate) => barrier.promise.then(() => {
    try { return pipeline.admit(candidate, { topic: 'bids', key, payload }); } catch { return null; }
  }));
  barrier.resolve();
  const receipts = await Promise.all(results);
  assert.equal(receipts.filter(Boolean).length, 1);
  key.fill(120); payload.fill(121); await tick();
  assert.equal(observed.key.toString(), 'k'); assert.equal(observed.payload.toString(), 'abc');
  counters(pipeline.snapshot());
  held.resolve(); await tick();
  assert.throws(() => pipeline.admit(pipeline.beginCandidate(), { topic: 'bids', key: 'k', payload: Buffer.from('abcd') }), /backpressure/i);
  assert.equal(pipeline.snapshot().outstanding_bytes, 0); counters(pipeline.snapshot());
  await pipeline.stop();
});

test('precommit deadline fences late admission and preserves preparation until work ends', async () => {
  let now = 0, dispatched = 0;
  const pipeline = new OwnedPipeline({ settings: settings({ BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS: '10' }), now: () => now,
    available: () => true, dispatch: async () => { dispatched++; }, teardown: async () => {} });
  const release = pipeline.acquirePreparation(), candidate = pipeline.beginCandidate();
  now = 11;
  assert.throws(() => pipeline.admit(candidate, { topic: 'bids', key: 'k', payload: Buffer.from('abc') }), /unavailable/i);
  assert.equal(pipeline.snapshot().preparation_count, 1);
  assert.throws(() => pipeline.admit(candidate, { topic: 'bids', key: 'k', payload: Buffer.from('abc') }), /unavailable/i);
  assert.equal(pipeline.snapshot().admission_rejected, 1); assert.equal(pipeline.snapshot().deadline_observations, 1);
  release(); await tick(); assert.equal(dispatched, 0); await pipeline.stop();
});

test('an already successful client outcome cannot authorize a late HTTP response decision', async () => {
  let now = 0;
  const pipeline = new OwnedPipeline({ settings: settings({ BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS: '10' }), now: () => now,
    available: () => true, dispatch: async () => {}, teardown: async () => {} });
  const receipt = pipeline.admit(pipeline.beginCandidate(), { topic: 'bids', key: 'k', payload: Buffer.from('abc') });
  await tick();
  now = 11;
  await assert.rejects(pipeline.confirm(receipt), /unavailable/i);
  assert.equal(pipeline.snapshot().client_succeeded, 1); assert.equal(pipeline.snapshot().client_failed, 0);
  assert.equal(pipeline.snapshot().deadline_observations, 1); await pipeline.stop();
});

test('preparation gate precedes body parsing, applies to HTTP-only and releases on malformed/oversized input', async () => {
  const server = await buildServer({ settings: settings({ BENCHMARK_DELIVERY_MODE: 'http-only', BENCHMARK_PREPARATION_MAX_REQUESTS: '1' }), logger: false });
  try {
    const release = server.pipeline.acquirePreparation();
    assert.equal((await server.inject({ method: 'POST', url: '/bid-request', headers: { 'content-type': 'application/json' }, payload: '{' })).statusCode, 503);
    release();
    assert.equal((await server.inject({ method: 'POST', url: '/bid-request', headers: { 'content-type': 'application/json' }, payload: '{' })).statusCode, 400);
    assert.equal((await server.inject({ method: 'POST', url: '/bid-request', headers: { 'content-type': 'application/json' }, payload: ' '.repeat(65537) })).statusCode, 413);
    assert.equal(server.pipeline.snapshot().preparation_count, 0);
    assert.equal((await request(server)).statusCode, 200);
    assert.equal(server.pipeline.snapshot().admission_candidates, 0);
  } finally { await server.close(); }
});

test('invalid/filtered/HTTP-only paths never initialize, serialize or dispatch Kafka records', async () => {
  const server = await buildServer({ settings: settings({ BENCHMARK_DELIVERY_MODE: 'http-only' }), logger: false,
    serialize: () => { throw new Error('must not serialize'); },
    publisher: { start: async () => { throw new Error('must not initialize'); }, stop: async () => { throw new Error('must not stop absent producer'); }, publish: async () => { throw new Error('must not dispatch'); } } });
  try {
    assert.equal((await request(server)).statusCode, 200);
    assert.equal((await request(server, {})).statusCode, 400);
    assert.equal((await request(server, { ...bid, device: { lmt: 1 } })).statusCode, 204);
    assert.equal(server.pipeline.snapshot().admission_candidates, 0);
  } finally { await server.close(); }
});

test('drain holds credits until confirmed teardown and reports residual unknown; late callback is fenced', async () => {
  const client = deferred(), teardownStarted = deferred(), teardownFinished = deferred();
  const pipeline = new OwnedPipeline({ settings: settings({ BENCHMARK_DRAIN_TIMEOUT_MS: '10' }), available: () => true,
    dispatch: () => client.promise, teardown: () => { teardownStarted.resolve(); return teardownFinished.promise; } });
  const receipt = pipeline.admit(pipeline.beginCandidate(), { topic: 'bids', key: 'k', payload: Buffer.from('abc') });
  await tick(); const stopping = pipeline.stop(); await teardownStarted.promise;
  assert.equal(pipeline.snapshot().teardown_confirmed, false);
  assert.equal(pipeline.snapshot().outstanding, 1); assert.equal(pipeline.snapshot().abandoned_unknown, 0);
  assert.equal(pipeline.acquirePreparation(), null);
  teardownFinished.resolve(); await stopping;
  assert.equal(pipeline.snapshot().teardown_confirmed, true);
  assert.equal(pipeline.snapshot().abandoned_unknown, 1); assert.equal(pipeline.snapshot().drain_result, 'deadline_exceeded');
  client.resolve(); await tick();
  assert.equal(pipeline.snapshot().client_succeeded, 0); assert.equal((await receipt.outcome).ok, false); counters(pipeline.snapshot());
});

test('unconfirmed teardown retains unresolved credits and reports failure', async () => {
  const client = deferred();
  const pipeline = new OwnedPipeline({ settings: settings({ BENCHMARK_DRAIN_TIMEOUT_MS: '10' }), available: () => true,
    dispatch: () => client.promise, teardown: async () => { throw new Error('teardown failed'); } });
  pipeline.admit(pipeline.beginCandidate(), { topic: 'bids', key: 'k', payload: Buffer.from('abc') });
  await tick(); await assert.rejects(pipeline.stop(), /teardown failed/);
  assert.equal(pipeline.snapshot().teardown_confirmed, false);
  assert.equal(pipeline.snapshot().outstanding, 1); assert.equal(pipeline.snapshot().abandoned_unknown, 0);
  assert.equal(pipeline.snapshot().drain_result, 'teardown_unconfirmed'); counters(pipeline.snapshot());
  client.resolve(); await tick();
});

test('worker static shares stay within container budgets and invalid/ack-zero profiles fail startup', () => {
  const config = settings({ HTTP_SERVER_WORKERS: '3' });
  assert.ok(config.pipelineRecordLimit * 3 <= 1024); assert.ok(config.pipelineByteLimit * 3 <= 16777216); assert.ok(config.preparationLimit * 3 <= 128);
  assert.throws(() => settings({ HTTP_SERVER_WORKERS: '129' }), /budget/);
  assert.throws(() => settings({ BENCHMARK_PIPELINE_MAX_RECORDS: '1junk' }), /positive integer/);
  assert.throws(() => settings({ BENCHMARK_DELIVERY_MODE: 'confirm', BENCHMARK_KAFKA_ACKS: '0' }), /unsupported/);
});

test('SIGTERM waits for both HTTP-only cluster workers to emit settled drain snapshots', { timeout: 10000 }, async () => {
  const child = spawn(process.execPath, [resolve(__dirname, '../src/server.js')], { env: { ...process.env, HOST: '127.0.0.1', PORT: '0', HTTP_SERVER_WORKERS: '2', BENCHMARK_DELIVERY_MODE: 'http-only' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '', stderr = '';
  const listening = deferred();
  child.stdout.on('data', (chunk) => { stdout += chunk; if ((stdout.match(/Server listening at/g) || []).length >= 2) listening.resolve(); });
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  const exit = once(child, 'exit');
  try {
    await Promise.race([listening.promise, exit.then(() => { throw new Error(`child exited early: ${stderr}\n${stdout}`); })]);
    child.kill('SIGTERM');
    const [code, signal] = await exit;
    assert.equal(signal, null); assert.equal(code, 0, stderr);
    const snapshots = stdout.split('\n').filter((line) => line.includes('"benchmark_state"')).map((line) => JSON.parse(line).benchmark_state);
    assert.equal(snapshots.length, 2); assert.ok(snapshots.every((state) => state.drain_result === 'settled' && state.preparation_count === 0 && state.outstanding === 0));
  } finally { if (child.exitCode == null) child.kill('SIGKILL'); }
});
