'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { buildServer } = require('../src/app');
const { loadSettings } = require('../src/config');

const cases = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../tests/conformance/corpus.json'))).cases.filter(value => value.scenario);
const token = 'isolated-conformance-test';
const config = (mode, enabled = true) => loadSettings({ BENCHMARK_DELIVERY_MODE: mode, HTTP_SERVER_WORKERS: '1',
  BENCHMARK_CONFORMANCE: enabled ? '1' : '0', BENCHMARK_CONFORMANCE_TOKEN: token,
  BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS: '150', BENCHMARK_DRAIN_TIMEOUT_MS: '150' });
const pause = () => new Promise(resolve => setTimeout(resolve, 1));
async function until(server, predicate) {
  const deadline = performance.now() + 2000;
  while (performance.now() < deadline) {
    const state = server.pipeline.conformance.snapshot();
    if (predicate(state)) return state;
    await pause();
  }
  throw new Error('Actual conformance milestone not observed: ' + JSON.stringify(server.pipeline.conformance.snapshot()));
}
async function command(server, action, extra = {}) {
  const reply = await server.inject({ method: 'POST', url: '/benchmark/conformance',
    headers: { 'content-type': 'application/json', 'x-conformance-token': token }, payload: JSON.stringify({ action, ...extra }) });
  assert.equal(reply.statusCode, 200, reply.body);
  return reply.json();
}
function bid(server, fixture, id) {
  let payload = fixture.request.body.data;
  if (id) { const value = JSON.parse(payload); value.id = id; payload = JSON.stringify(value); }
  return server.inject({ method: 'POST', url: '/bid-request', headers: { 'content-type': 'application/json' }, payload });
}
function delta(state, baseline) {
  const result = {};
  for (const key of ['admission_candidates', 'admitted', 'admission_rejected', 'admission_undecided', 'client_succeeded', 'client_failed', 'abandoned_unknown', 'outstanding', 'preparation_rejected']) result[key] = state[key] - baseline[key];
  return result;
}
function compare(actual, expected) { for (const [key, value] of Object.entries(expected)) assert.equal(actual[key], value, key); }

test('adapters require explicit enablement and token before parsing; startup restrictions remain actual', async () => {
  for (const enabled of [false, true]) {
    const server = await buildServer({ settings: config('http-only', enabled), logger: false });
    try {
      const before = server.pipeline.snapshot();
      for (const supplied of [undefined, 'wrong']) {
        const response = await server.inject({ method: 'POST', url: '/benchmark/conformance',
          headers: { 'content-type': 'application/json', ...(supplied ? { 'x-conformance-token': supplied } : {}) }, payload: '{' });
        assert.equal(response.statusCode, 404);
      }
      assert.deepEqual(server.pipeline.snapshot(), before);
      if (!enabled) assert.equal(server.pipeline.conformance, null);
      else {
        const runtime = (await server.inject({ method: 'GET', url: '/benchmark/runtime' })).json();
        assert.equal(runtime.producer.instances, 0); assert.equal(runtime.producer.conformance_transport, true);
      }
    } finally { await server.close(); }
  }
  assert.throws(() => loadSettings({ HTTP_SERVER_WORKERS: '1', BENCHMARK_CONFORMANCE: '1' }), /nonempty token/);
  assert.throws(() => loadSettings({ HTTP_SERVER_WORKERS: '2', BENCHMARK_CONFORMANCE: '1', BENCHMARK_CONFORMANCE_TOKEN: token }), /one serving worker/);
  assert.throws(() => loadSettings({ HTTP_SERVER_WORKERS: '1', BENCHMARK_DELIVERY_MODE: 'confirm', BENCHMARK_KAFKA_ACKS: '0' }), /confirm.*unsupported/);
  for (const mode of ['http-only', 'enqueue']) assert.equal(loadSettings({ HTTP_SERVER_WORKERS: '1', BENCHMARK_DELIVERY_MODE: mode, BENCHMARK_KAFKA_ACKS: '0' }).kafkaAcks, 0);
});

test('an early timer notification cannot commit or expire a still-held real candidate', async () => {
  const server = await buildServer({ settings: config('enqueue'), logger: false });
  const originalTimer = global.setTimeout;
  let earlyNotification = false;
  try {
    const fixture = cases.find(value => value.scenario.fault === 'admission_deadline_then_late_receipt');
    await command(server, 'configure', { case_id: fixture.id, fault: fixture.scenario.fault });
    global.setTimeout = (callback, duration, ...args) => {
      if (!earlyNotification && duration >= 100 && duration <= 150) {
        earlyNotification = true;
        return originalTimer(callback, 1, ...args);
      }
      return originalTimer(callback, duration, ...args);
    };
    let finished = false;
    const response = bid(server, fixture).then(value => { finished = true; return value; });
    await until(server, value => value.held_admission === 1);
    await new Promise(resolve => originalTimer(resolve, 15));
    assert.equal(earlyNotification, true);
    assert.equal(finished, false);
    assert.equal(server.pipeline.snapshot().admission_undecided, 1);
    assert.equal(server.pipeline.snapshot().admitted, 0);
    assert.equal(server.pipeline.snapshot().preparation_count, 1);
    await command(server, 'release_admission');
    assert.equal((await response).statusCode, 200);
    await command(server, 'cleanup');
  } finally { global.setTimeout = originalTimer; await server.close(); }
});

test('reference setup cannot silently substitute smaller capacities; extra fixture attempts are bounded', async () => {
  const settings = config('enqueue');
  const small = await buildServer({ settings: { ...settings, pipelineRecordLimit: 2 }, logger: false });
  try {
    const rejected = await small.inject({ method: 'POST', url: '/benchmark/conformance', headers: { 'content-type': 'application/json', 'x-conformance-token': token }, payload: JSON.stringify({ action: 'configure', case_id: 'small', fault: 'backlog_record_limit' }) });
    assert.equal(rejected.statusCode, 409);
    assert.equal(small.pipeline.snapshot().admission_candidates, 0);
  } finally { await small.close(); }
  const server = await buildServer({ settings, logger: false });
  try {
    const fixture = cases.find(value => value.scenario.fault === 'confirm_ack_zero');
    await command(server, 'configure', { case_id: fixture.id, fault: fixture.scenario.fault });
    assert.equal((await bid(server, fixture, 'first')).statusCode, 200);
    assert.equal((await bid(server, fixture, 'second')).statusCode, 200);
    assert.equal((await bid(server, fixture, 'third')).statusCode, 503);
    await command(server, 'cleanup');
    assert.equal(server.pipeline.conformance.snapshot().records.length, 2);
    assert.equal(server.pipeline.snapshot().admission_rejected, 1);
  } finally { await server.close(); }
});

for (const mode of ['http-only', 'confirm', 'enqueue']) {
  test(`all 18 fault specifications use the actual Node handler/pipeline in ${mode}`, async () => {
    const server = await buildServer({ settings: config(mode), logger: false });
    try {
      const ordered = [...cases.filter(value => value.scenario.fault !== 'drain_deadline_unsettled'), ...cases.filter(value => value.scenario.fault === 'drain_deadline_unsettled')];
      for (const fixture of ordered) {
        const fault = fixture.scenario.fault;
        if (fixture.expectations[mode].outcome === 'unsupported') continue; // Actual startup guard tested separately.
        const initial = await command(server, 'configure', { case_id: fixture.id, fault });
        const baseline = initial.baseline;
        if (fault === 'final_slot_race' && mode !== 'http-only') {
          assert.equal(initial.setup.records, 1023); assert.equal(initial.state.outstanding, 1023);
          const attempts = ['race-a', 'race-b'].map(id => bid(server, fixture, id));
          await until(server, value => value.held_admission === 2);
          await command(server, 'release_admission');
          await until(server, value => value.held_delivery === 1);
          await command(server, 'release_delivery');
          const outcomes = await Promise.all(attempts);
          assert.deepEqual(outcomes.map(value => value.statusCode).sort(), [200, 503]);
          const final = server.pipeline.conformance.snapshot();
          const winner = outcomes[0].statusCode === 200 ? 'race-a' : 'race-b';
          assert.equal(Buffer.from(final.records[0].key_base64, 'base64').toString(), winner);
          assert.equal(final.state.outstanding, 1023); // Setup never settles in fixture release.
          const losingDelta = delta(final.state, baseline);
          losingDelta.admission_candidates--; losingDelta.admitted--; losingDelta.client_succeeded--;
          compare(losingDelta, fixture.expectations[mode].accounting);
          const releases = final.events.filter(value => value.name === 'admission_released');
          assert.equal(releases.length, 2);
          assert.ok(releases.every(value => value.sequence < final.events.find(value => value.name === 'receipt_committed').sequence));
        } else {
          if (mode !== 'http-only' && fault === 'backlog_record_limit') assert.equal(initial.setup.records, 1024);
          if (mode !== 'http-only' && fault === 'backlog_byte_limit') { assert.equal(initial.setup.bytes, 16777215); assert.ok(initial.setup.records < 1024); }
          if (fault === 'preparation_limit') assert.equal(initial.state.preparation_count, 128);
          let finished = false;
          const response = bid(server, fixture).then(value => { finished = true; return value; });
          if (mode !== 'http-only' && ['held_admission_then_success', 'held_admission_then_rejection', 'admission_deadline_then_late_receipt'].includes(fault)) {
            const held = await until(server, value => value.held_admission === 1);
            if (fault === 'held_admission_then_success') compare(delta(held.state, baseline), { admission_candidates: 1, admitted: 0, admission_undecided: 1 });
            else compare(delta(held.state, baseline), fixture.expectations[mode].intermediate_accounting);
            assert.equal(finished, false);
            if (fault === 'admission_deadline_then_late_receipt') {
              const expired = await response;
              assert.equal(expired.statusCode, 503, expired.body);
              assert.equal(server.pipeline.snapshot().preparation_count, 1);
              const heldLate = server.pipeline.conformance.snapshot();
              assert.equal(heldLate.held_admission, 0);
              assert.equal(heldLate.held_late_preparation, 1);
              await command(server, 'release_admission');
              assert.ok(server.pipeline.conformance.snapshot().events.some(value => value.name === 'late_receipt_fenced'));
            } else await command(server, fault === 'held_admission_then_rejection' ? 'reject_admission' : 'release_admission');
          }
          if (mode !== 'http-only' && ['held_admission_then_success', 'held_delivery_then_success', 'delivery_failure_after_receipt', 'response_deadline_then_late_success', 'duplicate_completion_callback', 'source_mutation_after_receipt', 'drain_deadline_unsettled'].includes(fault)) {
            const held = await until(server, value => value.held_delivery === 1 && value.caller_released);
            assert.equal(held.state.preparation_count, 0);
            compare(delta(held.state, baseline), fixture.expectations[mode].intermediate_accounting);
            if (mode === 'confirm') assert.equal(finished, false); else assert.equal((await response).statusCode, 200);
            if (fault === 'source_mutation_after_receipt') {
              assert.equal(held.records.length, 0);
              const mutated = await command(server, 'mutate_source');
              assert.notEqual(mutated.mutation.before_value_base64, mutated.mutation.after_value_base64);
            }
            if (fault === 'response_deadline_then_late_success') {
              await until(server, value => value.deadline_elapsed);
              if (mode === 'confirm') assert.equal((await response).statusCode, 503);
              assert.equal(server.pipeline.snapshot().outstanding, 1);
            }
            if (fault === 'drain_deadline_unsettled') await command(server, 'drain');
            else await command(server, fault === 'delivery_failure_after_receipt' ? 'fail_delivery' : 'release_delivery');
            if (fault === 'duplicate_completion_callback') {
              const before = server.pipeline.snapshot();
              await command(server, 'duplicate_completion');
              assert.deepEqual(server.pipeline.snapshot(), before);
            }
          }
          const actual = await response;
          assert.equal(actual.statusCode, fixture.expectations[mode].status, `${mode} ${fault}: ${actual.body}`);
          if (actual.statusCode === 200) assert.deepEqual(actual.json(), { status: 'accepted' });
          const final = await until(server, value => value.state.admission_undecided === 0 && value.state.outstanding === baseline.outstanding + fixture.expectations[mode].accounting.outstanding && (fault === 'drain_deadline_unsettled' || value.held_delivery === 0));
          compare(delta(final.state, baseline), fixture.expectations[mode].accounting);
          if (fixture.expectations[mode].record) {
            assert.equal(final.records.length, 1, fault);
            assert.equal(Buffer.from(final.records[0].key_base64, 'base64').toString(), fixture.expectations[mode].record.key);
            assert.deepEqual(JSON.parse(Buffer.from(final.records[0].value_base64, 'base64')), fixture.expectations[mode].record.value);
            assert.equal(final.records[0].topic, 'bids');
          } else assert.equal(final.records.length, 0, fault);
        }
        if (fault !== 'drain_deadline_unsettled' || mode === 'http-only') {
          await command(server, 'cleanup');
          assert.equal(server.pipeline.snapshot().outstanding, 0); assert.equal(server.pipeline.snapshot().preparation_count, 0);
        }
      }
    } finally { await server.close(); }
  });
}
