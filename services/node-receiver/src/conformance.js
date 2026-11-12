'use strict';

const { timingSafeEqual } = require('node:crypto');
const { PublishUnavailableError } = require('./publisher');
const { PublishDeadlineError } = require('./pipeline');

const FAULTS = new Set(['serialization_failure', 'producer_unavailable', 'admission_throw',
  'admission_failed_future', 'held_admission_then_success', 'held_delivery_then_success',
  'delivery_failure_after_receipt', 'response_deadline_then_late_success', 'backlog_record_limit',
  'backlog_byte_limit', 'preparation_limit', 'final_slot_race', 'drain_deadline_unsettled',
  'confirm_ack_zero', 'held_admission_then_rejection', 'admission_deadline_then_late_receipt',
  'duplicate_completion_callback', 'source_mutation_after_receipt']);
const ADMISSION_HOLDS = new Set(['held_admission_then_success', 'held_admission_then_rejection',
  'admission_deadline_then_late_receipt', 'final_slot_race']);
const DELIVERY_HOLDS = new Set(['held_admission_then_success', 'held_delivery_then_success', 'delivery_failure_after_receipt',
  'response_deadline_then_late_success', 'drain_deadline_unsettled', 'final_slot_race',
  'duplicate_completion_callback', 'source_mutation_after_receipt']);
const nextTurn = () => new Promise(resolve => setImmediate(resolve));

// Enabled only in an isolated, token-gated conformance process. All accounting
// belongs to OwnedPipeline; this ledger contains observations and real leases.
class FaultAdapter {
  constructor(pipeline, settings) {
    this.pipeline = pipeline;
    this.settings = settings;
    this.closed = false;
    this.configuring = false;
    this.busy = false;
    this.reset();
    pipeline.conformance = this;
  }

  reset() {
    this.caseId = null; this.fault = null; this.events = []; this.records = [];
    this.fixture = new Map(); this.setupIds = new Set(); this.admissions = new Map();
    this.deliveries = new Map(); this.preparations = []; this.completedIds = new Set();
    this.setup = { records: 0, bytes: 0, preparations: 0 };
    this.mutation = null; this.drain = null;
    this.releasedCallers = new Set();
    this.baseline = this.pipeline.snapshot();
  }

  authorized(headers) {
    const token = headers['x-conformance-token'];
    if (typeof token !== 'string') return false;
    const left = Buffer.from(token), right = Buffer.from(this.settings.conformanceToken);
    return left.length === right.length && timingSafeEqual(left, right);
  }

  note(name, id, extra = {}) {
    if (this.events.length >= 256) return;
    const key = this.fixture.get(id)?.key;
    this.events.push({ sequence: this.events.length + 1, name, ...(key === undefined ? {} : { key }), ...extra });
  }

  available() { return !this.closed && this.fault !== 'producer_unavailable'; }
  serializationCheck() {
    if (this.fault === 'serialization_failure') throw new Error('Injected serialization boundary failure');
  }

  committed(id, deadline, source) {
    if (this.configuring) { this.setupIds.add(id); return; }
    const key = source.key.toString('utf8');
    this.fixture.set(id, { key, deadline, source: this.fault === 'source_mutation_after_receipt' ? source : null });
    this.note('receipt_committed', id);
  }

  outcome(id, result) {
    if (this.setupIds.has(id)) return;
    if (this.fixture.has(id)) {
      this.completedIds.add(id);
      this.note(result, id);
    }
  }

  async admit(candidate, record, retainPreparation) {
    if (this.fixture.size >= 2) {
      this.pipeline.reject(candidate, 'fixture_bound');
      throw new PublishUnavailableError(new Error('Conformance fixture accepts at most two contenders'));
    }
    if (this.fault === 'admission_throw' || this.fault === 'admission_failed_future') {
      try {
        if (this.fault === 'admission_throw') throw new Error('Injected admission throw');
        await Promise.reject(new Error('Injected failed admission stage'));
      } catch (error) {
        this.pipeline.reject(candidate, this.fault);
        this.note('admission_rejected', undefined, { key: record.key.toString('utf8') });
        throw new PublishUnavailableError(error);
      }
    }
    if (!ADMISSION_HOLDS.has(this.fault)) {
      try { return this.pipeline.admit(candidate, record); }
      catch (error) { this.note('admission_rejected', undefined, { key: record.key.toString('utf8') }); throw error; }
    }
    const held = { candidate, record, releasePreparation: retainPreparation(), timedOut: false };
    this.fixture.set(candidate.id, { key: record.key.toString('utf8'), deadline: candidate.deadline, source: null });
    const barrier = new Promise(resolve => { held.release = resolve; });
    this.admissions.set(candidate.id, held);
    this.note('admission_held', candidate.id);
    let timer;
    const expiry = new Promise(resolve => {
      const observeExpiry = () => {
        const remaining = candidate.deadline - this.pipeline.now();
        if (remaining > 0) timer = setTimeout(observeExpiry, Math.max(1, Math.ceil(remaining)));
        else resolve('expired');
      };
      timer = setTimeout(observeExpiry, Math.max(1, Math.ceil(candidate.deadline - this.pipeline.now())));
    });
    const decision = await Promise.race([barrier, expiry]);
    clearTimeout(timer);
    if (decision === 'expired') {
      held.timedOut = true;
      // The real admission deadline check rejects/fences the actual candidate.
      try { this.pipeline.admit(candidate, record); }
      catch (error) { this.note('deadline_observed', candidate.id); throw error; }
      throw new Error('Admission deadline fence did not reject an expired candidate');
    }
    try {
      if (decision === 'reject') {
        this.pipeline.reject(candidate, 'deferred_admission_rejected');
        this.note('admission_rejected', candidate.id);
        throw new PublishUnavailableError(new Error('Deferred admission rejected'));
      }
      return this.pipeline.admit(candidate, record);
    } catch (error) {
      if (!(error instanceof PublishUnavailableError)) this.note('admission_rejected', candidate.id);
      throw error;
    } finally {
      this.admissions.delete(candidate.id);
      held.releasePreparation();
    }
  }

  capture(record) {
    if (this.records.length >= 16) throw new Error('Conformance fixture record bound exceeded');
    this.records.push({ topic: record.topic, key_base64: record.key.toString('base64'), value_base64: record.payload.toString('base64') });
  }

  dispatch(record, id) {
    if (this.closed) return new Promise(() => {}); // No callback after certified teardown.
    const setup = this.setupIds.has(id);
    if (!setup && this.fault !== 'source_mutation_after_receipt') this.capture(record);
    if (!setup && !DELIVERY_HOLDS.has(this.fault)) return Promise.resolve();
    const outcome = new Promise((resolve, reject) => this.deliveries.set(id, { record, resolve, reject, setup }));
    if (!setup) this.note('delivery_held', id);
    return outcome;
  }

  async configure(command) {
    if (!FAULTS.has(command.fault) || typeof command.case_id !== 'string' || !command.case_id || command.case_id.length > 128) throw new Error('Unknown fault or invalid case_id');
    const state = this.pipeline.snapshot();
    if (this.closed || !state.accepting || state.outstanding || state.preparation_count || state.admission_undecided) throw new Error('Cleanup live work before configure');
    if (this.settings.pipelineRecordLimit !== 1024 || this.settings.pipelineByteLimit !== 16777216 || this.settings.preparationLimit !== 128) throw new Error('Conformance reference capacities must remain 1024 records/16 MiB/128 preparations');
    this.reset(); this.caseId = command.case_id; this.fault = command.fault;
    if (this.settings.isHttpOnly() && this.fault !== 'preparation_limit') { this.baseline = this.pipeline.snapshot(); return; }
    this.configuring = true;
    try {
      if (['backlog_record_limit', 'final_slot_race', 'backlog_byte_limit'].includes(this.fault)) {
        const wanted = this.fault === 'final_slot_race' ? this.settings.pipelineRecordLimit - 1 : this.settings.pipelineRecordLimit;
        let remaining = this.fault === 'backlog_byte_limit' ? this.settings.pipelineByteLimit - 1 : 0;
        for (let index = 0; this.fault === 'backlog_byte_limit' ? remaining > 0 : index < wanted; index++) {
          const key = Buffer.from('__conformance_setup_' + index);
          const size = this.fault === 'backlog_byte_limit' ? Math.min(65536, remaining) : key.length + 1;
          if (size < key.length) throw new Error('Byte setup cannot fit bounded setup key');
          this.pipeline.admit(this.pipeline.beginCandidate(), { topic: this.settings.kafkaTopic, key, payload: Buffer.alloc(size - key.length, 120) });
          this.setup.records++; this.setup.bytes += size;
          remaining -= size;
        }
      } else if (this.fault === 'preparation_limit') {
        for (let index = 0; index < this.settings.preparationLimit; index++) {
          const release = this.pipeline.acquirePreparation();
          if (!release) throw new Error('Actual preparation setup rejected');
          this.preparations.push(release); this.setup.preparations++;
        }
      }
    } finally { this.configuring = false; }
    await nextTurn();
    this.baseline = this.pipeline.snapshot();
  }

  async releaseAdmission(reject = false, cleanup = false) {
    if (!cleanup && this.fault === 'final_slot_race' && this.admissions.size !== 2) throw new Error('Race requires two held actual contenders');
    for (const [id, held] of [...this.admissions]) {
      this.note('admission_released', id, { decision: reject ? 'reject' : 'accept' });
      if (held.timedOut) {
        let fenced = false;
        try { this.pipeline.admit(held.candidate, held.record); } catch { fenced = true; }
        if (!fenced) throw new Error('Late receipt unexpectedly committed');
        this.note('late_receipt_fenced', id);
        this.admissions.delete(id); held.releasePreparation();
      } else held.release(reject ? 'reject' : 'accept');
    }
    await nextTurn();
  }

  async releaseDelivery(failed = false, includeSetup = false) {
    for (const [id, held] of [...this.deliveries]) {
      if (held.setup && !includeSetup) continue;
      this.deliveries.delete(id);
      if (!held.setup) {
        if (this.fault === 'source_mutation_after_receipt') this.capture(held.record);
        this.note('delivery_released', id);
      }
      if (failed) held.reject(new PublishUnavailableError(new Error('Injected post-receipt client failure')));
      else held.resolve();
    }
    await nextTurn();
  }

  mutateSource() {
    const fixture = [...this.fixture.values()].find(value => value.source);
    if (!fixture) throw new Error('No actual caller buffer retained for mutation');
    const source = fixture.source;
    const before = source.payload.toString('base64');
    source.payload.fill(90); source.key.fill(88); source.topic += '-caller-reused';
    this.mutation = { performed: true, evidence: 'Mutated the original handler serialization Buffer/key and rebound its topic after OwnedPipeline copied commitment; dispatch retains the original owned bytes', before_value_base64: before, after_value_base64: source.payload.toString('base64') };
    this.note('source_mutated', undefined, { key: fixture.key });
  }

  duplicateCompletion() {
    if (!this.completedIds.size) throw new Error('First terminal callback must be observed before duplicating it');
    for (const id of this.completedIds) {
      this.pipeline.settle(id, 'client_succeeded');
      this.note('duplicate_completion', id);
    }
  }

  callerReleased(id) {
    this.releasedCallers.add(id);
    this.note('caller_released', id, { evidence: 'Handler cleared its request body, model, payload and source-record bindings and returned its actual preparation lease before confirm. Original source buffers retained only for the explicit mutation schedule; no GC claim.' });
  }

  async teardown() {
    this.closed = true;
    // No deferred outcome is invoked after teardown. Pending promises are
    // intentionally unresolved; the real pipeline classifies unknown credits.
    this.deliveries.clear();
    this.note('teardown_confirmed');
  }

  async command(command) {
    if (this.busy) throw new Error('Conformance control command already active');
    this.busy = true;
    try {
      if (this.closed) throw new Error('Conformance transport is closed');
      switch (command.action) {
        case 'configure': await this.configure(command); break;
        case 'release_admission': await this.releaseAdmission(); break;
        case 'reject_admission': await this.releaseAdmission(true); break;
        case 'release_delivery': await this.releaseDelivery(); break;
        case 'fail_delivery': await this.releaseDelivery(true); break;
        case 'mutate_source': this.mutateSource(); break;
        case 'duplicate_completion': this.duplicateCompletion(); break;
        case 'cleanup':
          await this.releaseAdmission(true, true);
          await this.releaseDelivery(false, true);
          for (const release of this.preparations.splice(0)) release();
          if (!await this.pipeline.waitIdle(this.settings.httpOutcomeTimeoutMs)) throw new Error('Cleanup did not settle actual pipeline');
          break;
        case 'drain': {
          this.note('drain_started'); const before = this.pipeline.snapshot();
          const after = await this.pipeline.stop();
          this.drain = { before, after }; break;
        }
        default: throw new Error('Unknown conformance action');
      }
      return this.snapshot();
    } finally { this.busy = false; }
  }

  snapshot() {
    const elapsed = [...this.fixture.values()].some(value => this.pipeline.now() >= value.deadline);
    if (elapsed && !this.events.some(event => event.name === 'deadline_observed')) this.note('deadline_observed');
    return { enabled: true, case_id: this.caseId, fault: this.fault, events: this.events.slice(), state: this.pipeline.snapshot(), baseline: this.baseline,
      held_admission: [...this.admissions.values()].filter(value => value.candidate.pending).length,
      held_late_preparation: [...this.admissions.values()].filter(value => value.timedOut).length,
      held_delivery: [...this.deliveries.values()].filter(value => !value.setup).length,
      records: this.records.slice(), setup: { ...this.setup }, mutation: this.mutation, drain: this.drain, deadline_elapsed: elapsed, caller_released: this.releasedCallers.size > 0 };
  }
}

module.exports = { FaultAdapter };
