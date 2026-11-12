'use strict';

const { performance } = require('node:perf_hooks');
const { PublishBackpressureError, PublishUnavailableError } = require('./publisher');

class PublishDeadlineError extends PublishUnavailableError {
  constructor() { super(new Error('HTTP outcome deadline')); this.name = 'PublishDeadlineError'; }
}

// The registry owns record bytes until a client outcome or confirmed teardown.
// SDK dispatch happens after commitment; an SDK promise is never admission.
class OwnedPipeline {
  constructor({ settings, available, dispatch, teardown, now = () => performance.now() }) {
    this.settings = settings;
    this.available = available;
    this.dispatch = dispatch;
    this.teardown = teardown;
    this.now = now;
    this.accepting = true;
    this.entries = new Map();
    this.candidates = new Set();
    this.sequence = 0;
    this.preparing = 0;
    this.bytes = 0;
    this.waiters = new Set();
    this.stopOperation = null;
    this.counters = {
      admission_candidates: 0, admitted: 0, admission_rejected: 0,
      admission_undecided: 0, client_succeeded: 0, client_failed: 0,
      abandoned_unknown: 0, preparation_rejected: 0, deadline_observations: 0,
    };
    this.reasons = {};
    this.drainResult = 'not_started';
    this.teardownConfirmed = false;
    this.httpStatuses = {};
    this.intakeClosedAt = null;
    this.conformance = null; // No adapter or fault ledger allocated normally.
  }

  acquirePreparation() {
    if (!this.accepting || this.preparing >= this.settings.preparationLimit) {
      this.counters.preparation_rejected++;
      this.reasons[!this.accepting ? 'intake_closed' : 'preparation_full'] = (this.reasons[!this.accepting ? 'intake_closed' : 'preparation_full'] || 0) + 1;
      return null;
    }
    this.preparing++;
    let released = false;
    return () => {
      if (!released) { released = true; this.preparing--; this.changed(); }
    };
  }

  beginCandidate() {
    const candidate = { id: ++this.sequence, deadline: this.now() + this.settings.httpOutcomeTimeoutMs, pending: true };
    this.counters.admission_candidates++;
    this.counters.admission_undecided++;
    this.candidates.add(candidate);
    return candidate;
  }

  reject(candidate, reason) {
    if (!candidate.pending) return;
    candidate.pending = false;
    this.candidates.delete(candidate);
    this.counters.admission_undecided--;
    this.counters.admission_rejected++;
    this.reasons[reason] = (this.reasons[reason] || 0) + 1;
    this.changed();
  }

  admit(candidate, record) {
    let owned;
    try {
      owned = { topic: String(record.topic), key: Buffer.from(record.key), payload: Buffer.from(record.payload) };
    } catch (error) {
      this.reject(candidate, 'serialization');
      throw error;
    }
    const size = owned.key.length + owned.payload.length;
    let reason;
    if (!candidate.pending) reason = 'candidate_fenced';
    else if (this.now() >= candidate.deadline) reason = 'deadline';
    else if (!this.accepting) reason = 'intake_closed';
    else if (!this.available()) reason = 'producer_unavailable';
    else if (this.entries.size >= this.settings.pipelineRecordLimit) reason = 'record_capacity';
    else if (this.bytes + size > this.settings.pipelineByteLimit) reason = 'byte_capacity';
    if (reason) {
      this.reject(candidate, reason);
      if (reason === 'deadline') { this.counters.deadline_observations++; throw new PublishDeadlineError(); }
      if (reason.endsWith('capacity')) throw new PublishBackpressureError(new Error(reason));
      throw new PublishUnavailableError(new Error(reason));
    }
    candidate.pending = false;
    this.candidates.delete(candidate);
    this.counters.admission_undecided--;
    this.counters.admitted++;
    this.bytes += size;
    let resolveOutcome;
    const outcome = new Promise((resolve) => { resolveOutcome = resolve; });
    const entry = { id: candidate.id, record: owned, size, resolveOutcome };
    this.entries.set(entry.id, entry);
    this.conformance?.committed(entry.id, candidate.deadline, record);
    // The callback count is bounded by owned slots. Avoid starting SDK work
    // before the receipt exists, including an immediately rejected send.
    setImmediate(() => {
      if (!this.entries.has(entry.id)) return;
      Promise.resolve().then(() => this.dispatch(entry.record, entry.id)).then(
        () => this.settle(entry.id, 'client_succeeded'),
        (error) => this.settle(entry.id, 'client_failed', error)
      );
    });
    this.changed();
    return { id: entry.id, outcome, deadline: candidate.deadline };
  }

  settle(id, result, error) {
    const entry = this.entries.get(id);
    if (!entry) return false;
    this.entries.delete(id);
    this.bytes -= entry.size;
    this.counters[result]++;
    entry.resolveOutcome({ ok: result === 'client_succeeded', error: error || (result !== 'client_succeeded' ? new PublishUnavailableError(new Error(result)) : undefined) });
    this.changed();
    this.conformance?.outcome(id, result);
    return true;
  }

  async confirm(receipt) {
    const remaining = receipt.deadline - this.now();
    if (remaining <= 0) { this.counters.deadline_observations++; throw new PublishDeadlineError(); }
    let timer;
    const deadline = new Promise((resolve) => {
      timer = setTimeout(() => {
        this.counters.deadline_observations++;
        resolve({ ok: false, error: new PublishDeadlineError() });
      }, remaining);
    });
    try {
      const result = await Promise.race([receipt.outcome, deadline]);
      if (!(result.error instanceof PublishDeadlineError) && this.now() >= receipt.deadline) {
        this.counters.deadline_observations++;
        throw new PublishDeadlineError();
      }
      if (!result.ok) throw result.error;
    } finally { clearTimeout(timer); }
  }

  closeIntake() {
    if (this.intakeClosedAt == null) this.intakeClosedAt = this.now();
    this.accepting = false;
    for (const candidate of this.candidates) this.reject(candidate, 'intake_closed');
    this.changed();
  }

  changed() { for (const wake of this.waiters) wake(); this.waiters.clear(); }

  async waitIdle(timeoutMs) {
    const deadline = this.now() + timeoutMs;
    while (this.entries.size || this.preparing || this.candidates.size) {
      const remaining = deadline - this.now();
      if (remaining <= 0) return false;
      await new Promise((resolve) => {
        let timer;
        const wake = () => { clearTimeout(timer); this.waiters.delete(wake); resolve(); };
        this.waiters.add(wake);
        timer = setTimeout(wake, remaining);
      });
    }
    return true;
  }

  stop() {
    if (this.stopOperation) return this.stopOperation;
    this.closeIntake();
    this.stopOperation = this.finishStop();
    return this.stopOperation;
  }

  async finishStop() {
    const idle = await this.waitIdle(Math.max(0, this.intakeClosedAt + this.settings.drainTimeoutMs - this.now()));
    // Teardown has a separate, bounded 5s grace. A failed/hung teardown does
    // not prove cancellation and must not free credits or fabricate outcomes.
    let timer;
    try {
      await Promise.race([
        Promise.resolve().then(() => this.teardown()),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Producer teardown unconfirmed')), 5000); }),
      ]);
      this.teardownConfirmed = true;
      for (const id of this.entries.keys()) this.settle(id, 'abandoned_unknown');
      this.drainResult = idle && !this.preparing && !this.candidates.size ? 'settled' : 'deadline_exceeded';
    } catch (error) {
      this.drainResult = 'teardown_unconfirmed';
      throw error;
    } finally { clearTimeout(timer); }
    return this.snapshot();
  }

  snapshot() {
    return {
      ...this.counters, outstanding: this.entries.size, outstanding_bytes: this.bytes,
      preparation_count: this.preparing, admission_rejection_reasons: { ...this.reasons },
      accepting: this.accepting, drain_result: this.drainResult, teardown_confirmed: this.teardownConfirmed,
      http_status_counts: { ...this.httpStatuses }, pid: process.pid, scope: 'worker_process',
      configured_bounds: { max_records: this.settings.pipelineMaxRecords, max_bytes: this.settings.pipelineMaxBytes, max_preparations: this.settings.preparationMaxRequests },
      bounds: {
        worker_count: this.settings.httpServerWorkers,
        pipeline_records: this.settings.pipelineRecordLimit,
        pipeline_bytes: this.settings.pipelineByteLimit,
        preparation_requests: this.settings.preparationLimit,
        http_outcome_timeout_ms: this.settings.httpOutcomeTimeoutMs,
        drain_timeout_ms: this.settings.drainTimeoutMs,
      },
    };
  }
}

module.exports = { OwnedPipeline, PublishDeadlineError };
