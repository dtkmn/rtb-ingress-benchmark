'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { Writable } = require('node:stream');
const { buildServer } = require('../src/app');
const { loadSettings } = require('../src/config');
const { createKafkaPublisher } = require('../src/publisher');
const { readCgroup } = require('../src/cgroup');

const settings = (profile, mode = 'http-only') => loadSettings({ BENCHMARK_TELEMETRY_PROFILE: profile, BENCHMARK_DELIVERY_MODE: mode, HTTP_SERVER_WORKERS: '1', BENCHMARK_KAFKA_ACKS: '1', BENCHMARK_KAFKA_LINGER_MS: '0', BENCHMARK_KAFKA_BATCH_BYTES: '16384', BENCHMARK_KAFKA_RETRIES: '0', BENCHMARK_KAFKA_PRODUCER_POOL_SIZE: '2' });
const request = { method: 'POST', url: '/bid-request', headers: { 'content-type': 'application/json' }, payload: '{"id":"x","site":{},"device":{}}' };

test('controlled removes actual access logs while preserving lifecycle and F2/F3 outcomes; operational opts in', async () => {
  for (const profile of ['controlled', 'operational']) {
    let logs = '';
    const stream = new Writable({ write(chunk, _encoding, done) { logs += chunk; done(); } });
    const server = await buildServer({ settings: settings(profile), logger: { level: 'info', stream } });
    try {
      assert.equal((await server.inject(request)).statusCode, 200);
      const runtime = (await server.inject({ method: 'GET', url: '/benchmark/runtime' })).json();
      assert.equal(runtime.schema_version, 1); assert.equal(runtime.lane, 'node'); assert.equal(runtime.scope, 'worker_process');
      assert.equal(runtime.telemetry.access_log, profile === 'operational');
      assert.equal(runtime.runtime.http_parallelism.effective, 1);
      assert.ok(runtime.runtime.heap.max_bytes > 0);
      assert.equal(runtime.producer.instances, 0);
      assert.equal(runtime.producer.version, require('kafkajs/package.json').version);
      for (const control of Object.values(runtime.producer.controls)) {
        assert.equal(control.status, 'not_applicable'); assert.equal(control.applied, null); assert.equal(control.effective, null);
      }
      assert.equal(runtime.producer.controls.linger_ms.requested, '0');
      assert.equal(runtime.producer.controls.producer_pool_size.requested, '2');
      assert.equal(server.pipeline.snapshot().preparation_count, 0);
      assert.equal(server.pipeline.snapshot().admission_candidates, 0);
      assert.equal(logs.includes('incoming request'), profile === 'operational');
      assert.equal(logs.includes('request completed'), profile === 'operational');
    } finally { await server.close(); }
    assert.ok(logs.includes('Receiver drain finished'));
  }
});

test('controlled unexpected diagnostics are bounded and expected input errors stay silent', async () => {
  let logs = '';
  const stream = new Writable({ write(chunk, _encoding, done) { logs += chunk; done(); } });
  const server = await buildServer({ settings: settings('controlled'), logger: { level: 'info', stream } });
  server.get('/unexpected-error', () => { throw new Error('x'.repeat(4000)); });
  try {
    for (let i = 0; i < 5; i++) assert.equal((await server.inject({ method: 'GET', url: '/unexpected-error' })).statusCode, 500);
    assert.equal((logs.match(/Receiver error; aggregate outcome retained/g) || []).length, 3);
    assert.ok(!logs.includes('x'.repeat(513)));
    assert.equal((await server.inject({ ...request, payload: '{' })).statusCode, 400);
    assert.equal((await server.inject({ ...request, payload: ' '.repeat(65537) })).statusCode, 413);
    assert.ok(!logs.includes('Unhandled request error'));
  } finally { await server.close(); }
});

test('controlled also suppresses per-request closing-intake logs while retaining the final drain', async () => {
  for (const profile of ['controlled', 'operational']) {
    let logs = '', releaseClosing, enteredClosing;
    const entered = new Promise((resolve) => { enteredClosing = resolve; });
    const hold = new Promise((resolve) => { releaseClosing = resolve; });
    const stream = new Writable({ write(chunk, _encoding, done) { logs += chunk; done(); } });
    const server = await buildServer({ settings: settings(profile), logger: { level: 'info', stream } });
    server.addHook('preClose', async () => { enteredClosing(); await hold; });
    await server.ready();
    const closing = server.close();
    try {
      await entered;
      // The public routing entrypoint exercises Fastify's real closing gate;
      // server.inject itself deliberately refuses calls once close begins.
      const response = await require('light-my-request')(server.routing, request);
      assert.equal(response.statusCode, 503);
      assert.equal(logs.includes('request aborted - refusing to accept'), profile === 'operational');
    } finally { releaseClosing(); await closing; }
    assert.ok(logs.includes('Receiver drain finished'));
  }
});

test('SDK metadata retains actual constructor arguments and does not invent effective getters', async () => {
  const previous = process.env.KAFKAJS_LOG_LEVEL;
  process.env.KAFKAJS_LOG_LEVEL = 'DEBUG';
  const config = settings('controlled', 'confirm');
  const publisher = createKafkaPublisher(config);
  try {
    config.kafkaRetries = 99;
    config.requestedControls.BENCHMARK_KAFKA_RETRIES = '99';
    const meta = publisher.runtimeSnapshot();
    assert.equal(meta.instances, 1);
    assert.equal(meta.version, require('kafkajs/package.json').version);
    assert.equal(meta.controls.retries.applied, 0); assert.equal(meta.controls.retries.effective, null);
    assert.equal(meta.controls.retries.requested, '0');
    assert.equal(meta.controls.acks.applied, 1); assert.equal(meta.controls.acks.effective, null);
    for (const key of ['linger_ms', 'batch_bytes', 'producer_pool_size']) {
      assert.equal(meta.controls[key].status, 'unsupported'); assert.equal(meta.controls[key].applied, null); assert.equal(meta.controls[key].effective, null);
    }
    assert.equal(meta.controls.linger_ms.requested, '0'); assert.equal(meta.controls.batch_bytes.requested, '16384');
    assert.equal(meta.controls.max_in_flight_requests.applied, 1);
    assert.equal(meta.controls.idempotent.effective, false);
    assert.equal(process.env.KAFKAJS_LOG_LEVEL, 'NOTHING');
    assert.equal(meta.controls.sdk_log_level.requested, 'DEBUG'); assert.equal(meta.controls.sdk_log_level.effective, null);
  } finally {
    await publisher.stop();
    if (previous === undefined) delete process.env.KAFKAJS_LOG_LEVEL; else process.env.KAFKAJS_LOG_LEVEL = previous;
  }
});

test('runtime endpoint reports a disabled logger without inventing access logging or failing', async () => {
  const server = await buildServer({ settings: settings('operational'), logger: false });
  try {
    const response = await server.inject({ method: 'GET', url: '/benchmark/runtime' });
    assert.equal(response.statusCode, 200);
    assert.equal(response.json().telemetry.access_log, false);
    assert.equal(response.json().producer.instances, 0);
  } finally { await server.close(); }
});

test('process cgroup kernel readback resolves native membership and namespaced mounts; v1 fallback', () => {
  for (const [groups, mounts, files, version] of [
    ['0::/tenant/receiver', '1 2 0:1 / /sys/fs/cgroup rw - cgroup2 cgroup rw', { '/sys/fs/cgroup/tenant/receiver/memory.max': '805306368', '/sys/fs/cgroup/tenant/receiver/cpu.max': '200000 100000', '/sys/fs/cgroup/tenant/receiver/cpuset.cpus.effective': '2-3' }, 2],
    ['0::/', '1 2 0:1 /docker/id /sys/fs/cgroup rw - cgroup2 cgroup rw', { '/sys/fs/cgroup/memory.max': '805306368', '/sys/fs/cgroup/cpu.max': '200000 100000', '/sys/fs/cgroup/cpuset.cpus.effective': '2-3' }, 2],
    ['1:memory:/receiver\n2:cpu,cpuacct:/receiver\n3:cpuset:/receiver', '1 2 0:1 / /sys/fs/cgroup/memory rw - cgroup cgroup rw,memory\n2 2 0:2 / /sys/fs/cgroup/cpu rw - cgroup cgroup rw,cpu,cpuacct\n3 2 0:3 / /sys/fs/cgroup/cpuset rw - cgroup cgroup rw,cpuset', { '/sys/fs/cgroup/memory/receiver/memory.limit_in_bytes': '805306368', '/sys/fs/cgroup/cpu/receiver/cpu.cfs_quota_us': '200000', '/sys/fs/cgroup/cpu/receiver/cpu.cfs_period_us': '100000', '/sys/fs/cgroup/cpuset/receiver/cpuset.cpus': '2-3' }, 1],
  ]) {
    const read = (filename) => { if (filename === '/proc/self/cgroup') return groups; if (filename === '/proc/self/mountinfo') return mounts; if (files[filename] !== undefined) return files[filename]; throw new Error('missing'); };
    const result = readCgroup('linux', read);
    assert.equal(result.version, version); assert.equal(result.memory_max_bytes, 805306368);
    assert.equal(result.cpu_quota_us, 200000); assert.equal(result.cpu_period_us, 100000); assert.equal(result.cpuset_cpus_effective, '2-3');
  }
  const unavailable = readCgroup('darwin', () => { throw new Error('must not read kernel files'); });
  assert.equal(unavailable.version, null); assert.equal(unavailable.memory_max_bytes, null);
  const unlimited = readCgroup('linux', (filename) => ({ '/proc/self/cgroup': '0::/', '/proc/self/mountinfo': '1 2 0:1 / /sys/fs/cgroup rw - cgroup2 cgroup rw', '/sys/fs/cgroup/memory.max': 'max', '/sys/fs/cgroup/cpu.max': 'max 100000', '/sys/fs/cgroup/cpuset.cpus.effective': '0-3' }[filename]));
  assert.equal(unlimited.memory_max_bytes, null); assert.equal(unlimited.cpu_quota_us, null); assert.equal(unlimited.raw['memory.max'], 'max');
});

test('telemetry profile typos fail startup configuration', () => {
  assert.equal(loadSettings({ HTTP_SERVER_WORKERS: '1' }).telemetryProfile, 'controlled');
  assert.throws(() => loadSettings({ BENCHMARK_TELEMETRY_PROFILE: 'contrlled' }), /controlled or operational/);
});
