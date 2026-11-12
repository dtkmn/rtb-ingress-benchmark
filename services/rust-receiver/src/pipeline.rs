use serde::Serialize;
use std::collections::{BTreeMap, HashMap};
use std::future::Future;
use std::pin::Pin;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::time::{Duration, Instant};
use tokio::sync::{watch, Notify};
use tokio::task::JoinHandle;

pub type DispatchFuture = Pin<Box<dyn Future<Output = bool> + Send>>;
pub type TeardownFuture = Pin<Box<dyn Future<Output = Result<(), String>> + Send>>;

pub trait Transport: Send + Sync {
    fn dispatch(self: Arc<Self>, record: Arc<OwnedRecord>) -> DispatchFuture;
    fn teardown(self: Arc<Self>) -> TeardownFuture;
}

#[derive(Debug)]
pub struct OwnedRecord {
    pub topic: String,
    pub key: Vec<u8>,
    pub value: Vec<u8>,
}

impl OwnedRecord {
    fn size(&self) -> usize {
        self.key.len() + self.value.len()
    }
}

#[derive(Clone, Copy, Debug, Serialize)]
pub struct Limits {
    pub max_records: usize,
    pub max_bytes: usize,
    pub max_preparations: usize,
    pub http_outcome_timeout_ms: u64,
    pub drain_timeout_ms: u64,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_records: 1024,
            max_bytes: 16777216,
            max_preparations: 128,
            http_outcome_timeout_ms: 30000,
            drain_timeout_ms: 30000,
        }
    }
}

#[derive(Default, Serialize)]
struct Counts {
    admission_candidates: u64,
    admitted: u64,
    admission_rejected: u64,
    client_succeeded: u64,
    client_failed: u64,
    abandoned_unknown: u64,
    preparation_rejected: u64,
    deadline_observations: u64,
}

struct Entry {
    record: Arc<OwnedRecord>,
    outcome: watch::Sender<Option<bool>>,
    task: Option<JoinHandle<()>>,
}

struct Ledger {
    counts: Counts,
    candidates: HashMap<u64, Instant>,
    records: HashMap<u64, Entry>,
    next_attempt: u64,
    outstanding_bytes: usize,
    preparation_count: usize,
    closing: bool,
    available: bool,
    teardown_confirmed: bool,
    drain_started: Option<Instant>,
    drain_result: &'static str,
    http_status_counts: BTreeMap<u16, u64>,
    admission_rejection_reasons: BTreeMap<&'static str, u64>,
}

pub struct Pipeline {
    limits: Limits,
    transport: Option<Arc<dyn Transport>>,
    ledger: Mutex<Ledger>,
    changed: Notify,
}

pub struct Candidate {
    attempt: u64,
    deadline: Instant,
    pipeline: Arc<Pipeline>,
}

impl Candidate {
    pub fn attempt(&self) -> u64 {
        self.attempt
    }
    pub fn deadline(&self) -> Instant {
        self.deadline
    }
}

impl Drop for Candidate {
    fn drop(&mut self) {
        self.pipeline.reject(self.attempt, "cancelled");
    }
}

pub struct Receipt {
    #[allow(dead_code)]
    // Internal identity is exposed for adapters/fault evidence, never request ID.
    pub attempt: u64,
    deadline: Instant,
    outcome: watch::Receiver<Option<bool>>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::Semaphore;

    struct HeldTransport {
        calls: Mutex<Vec<(String, Vec<u8>, Vec<u8>)>>,
        started: Semaphore,
        release: Semaphore,
        failure: AtomicBool,
        teardown_ok: AtomicBool,
        teardown_called: AtomicBool,
    }

    impl HeldTransport {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                calls: Mutex::new(Vec::new()),
                started: Semaphore::new(0),
                release: Semaphore::new(0),
                failure: AtomicBool::new(false),
                teardown_ok: AtomicBool::new(true),
                teardown_called: AtomicBool::new(false),
            })
        }
    }

    impl Transport for HeldTransport {
        fn dispatch(self: Arc<Self>, record: Arc<OwnedRecord>) -> DispatchFuture {
            Box::pin(async move {
                self.calls.lock().unwrap().push((
                    record.topic.clone(),
                    record.key.clone(),
                    record.value.clone(),
                ));
                self.started.add_permits(1);
                self.release.acquire().await.unwrap().forget();
                !self.failure.load(Ordering::Acquire)
            })
        }
        fn teardown(self: Arc<Self>) -> TeardownFuture {
            Box::pin(async move {
                self.teardown_called.store(true, Ordering::Release);
                if self.teardown_ok.load(Ordering::Acquire) {
                    Ok(())
                } else {
                    Err("injected unconfirmed teardown".to_string())
                }
            })
        }
    }

    fn record() -> OwnedRecord {
        OwnedRecord {
            topic: "bids".into(),
            key: b"id".to_vec(),
            value: b"value".to_vec(),
        }
    }
    fn count(pipeline: &Pipeline, name: &str) -> u64 {
        pipeline.snapshot()[name].as_u64().unwrap()
    }
    fn balanced(p: &Pipeline) {
        assert_eq!(
            count(p, "admission_candidates"),
            count(p, "admitted") + count(p, "admission_rejected") + count(p, "admission_undecided")
        );
        assert_eq!(
            count(p, "admitted"),
            count(p, "client_succeeded")
                + count(p, "client_failed")
                + count(p, "abandoned_unknown")
                + count(p, "outstanding")
        );
    }

    async fn wait_terminal(p: &Pipeline) {
        tokio::time::timeout(Duration::from_secs(1), async {
            while count(p, "outstanding") != 0 {
                p.changed.notified().await;
            }
        })
        .await
        .expect("held SDK completion settles within the test deadline");
    }

    #[tokio::test]
    async fn ownership_timeout_late_outcome_and_duplicate_settlement() {
        let transport = HeldTransport::new();
        let p = Pipeline::new(Limits::default(), Some(transport.clone()));
        let mut source = b"original".to_vec();
        let mut receipt = p
            .commit(
                p.candidate(),
                OwnedRecord {
                    topic: "bids".into(),
                    key: b"id".to_vec(),
                    value: source.clone(),
                },
            )
            .unwrap();
        let attempt = receipt.attempt;
        source.fill(b'x');
        transport.started.acquire().await.unwrap().forget();
        assert_eq!(transport.calls.lock().unwrap()[0].2, b"original");
        receipt.deadline = Instant::now() - Duration::from_secs(1);
        assert!(!p.confirm(receipt).await);
        assert_eq!(count(&p, "outstanding"), 1);
        assert_eq!(count(&p, "outstanding_bytes"), 10);
        assert_eq!(count(&p, "client_failed"), 0);
        transport.release.add_permits(1);
        wait_terminal(&p).await;
        assert_eq!(count(&p, "client_succeeded"), 1);
        assert!(!p.settle(attempt, true));
        balanced(&p);
        assert_eq!(p.shutdown().await["drain_result"], "settled");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn concurrent_final_slot_is_atomic_and_byte_rejection_never_dispatches() {
        let transport = HeldTransport::new();
        let p = Pipeline::new(
            Limits {
                max_records: 1,
                max_bytes: 7,
                ..Limits::default()
            },
            Some(transport.clone()),
        );
        let barrier = Arc::new(tokio::sync::Barrier::new(2));
        let mut contenders = Vec::new();
        for _ in 0..2 {
            let candidate = p.candidate();
            let pipeline = p.clone();
            let barrier = barrier.clone();
            contenders.push(tokio::spawn(async move {
                barrier.wait().await;
                pipeline.commit(candidate, record()).is_ok()
            }));
        }
        let mut winners = 0;
        for contender in contenders {
            winners += usize::from(contender.await.unwrap());
        }
        assert_eq!(winners, 1);
        assert_eq!(count(&p, "outstanding"), 1);
        transport.started.acquire().await.unwrap().forget();
        transport.release.add_permits(1);
        wait_terminal(&p).await;
        let too_large = OwnedRecord {
            topic: "bids".into(),
            key: b"id".to_vec(),
            value: b"123456".to_vec(),
        };
        assert_eq!(
            p.commit(p.candidate(), too_large).err(),
            Some("byte_capacity")
        );
        assert_eq!(transport.calls.lock().unwrap().len(), 1);
        balanced(&p);
        p.shutdown().await;
    }

    #[tokio::test]
    async fn preparation_and_precommit_rejections_are_distinct_from_sdk_failure() {
        let p = Pipeline::new(
            Limits {
                max_preparations: 1,
                ..Limits::default()
            },
            None,
        );
        let lease = p.prepare().unwrap();
        assert!(p.prepare().is_none());
        assert_eq!(count(&p, "preparation_rejected"), 1);
        assert_eq!(count(&p, "admission_candidates"), 0);
        assert_eq!(p.commit(p.candidate(), record()).err(), Some("unavailable"));
        let candidate = p.candidate();
        p.serialization_failed(&candidate);
        assert_eq!(p.commit(candidate, record()).err(), Some("fenced"));
        assert_eq!(count(&p, "admission_rejected"), 2);
        lease.release();
        balanced(&p);

        let transport = HeldTransport::new();
        transport.failure.store(true, Ordering::Release);
        transport.release.add_permits(1);
        let p = Pipeline::new(Limits::default(), Some(transport));
        let receipt = p.commit(p.candidate(), record()).unwrap();
        assert!(!p.confirm(receipt).await);
        assert_eq!(count(&p, "admitted"), 1);
        assert_eq!(count(&p, "client_failed"), 1);
        assert_eq!(count(&p, "admission_rejected"), 0);
        balanced(&p);
        p.shutdown().await;
    }

    #[tokio::test]
    async fn drain_cannot_abandon_before_teardown_and_preserves_live_preparations() {
        let transport = HeldTransport::new();
        transport.teardown_ok.store(false, Ordering::Release);
        let p = Pipeline::new(
            Limits {
                drain_timeout_ms: 1,
                ..Limits::default()
            },
            Some(transport.clone()),
        );
        let lease = p.prepare().unwrap();
        p.commit(p.candidate(), record()).unwrap();
        transport.started.acquire().await.unwrap().forget();
        let unresolved = p.shutdown().await;
        assert_eq!(unresolved["drain_result"], "teardown_unconfirmed");
        assert_eq!(unresolved["outstanding"], 1);
        assert_eq!(unresolved["outstanding_bytes"], 7);
        assert_eq!(unresolved["preparation_count"], 1);
        assert_eq!(unresolved["abandoned_unknown"], 0);
        assert!(p.prepare().is_none());
        lease.release();
        transport.teardown_ok.store(true, Ordering::Release);
        let closed = p.shutdown().await;
        assert_eq!(closed["teardown_confirmed"], true);
        assert_eq!(closed["abandoned_unknown"], 1);
        assert_eq!(closed["outstanding"], 0);
        assert_eq!(closed["preparation_count"], 0);
        balanced(&p);
    }

    #[tokio::test]
    async fn expired_or_shutdown_candidate_cannot_commit_later() {
        let transport = HeldTransport::new();
        let p = Pipeline::new(Limits::default(), Some(transport));
        let mut candidate = p.candidate();
        candidate.deadline = Instant::now() - Duration::from_secs(1);
        assert_eq!(p.commit(candidate, record()).err(), Some("deadline"));
        let candidate = p.candidate();
        p.close_intake();
        assert_eq!(p.commit(candidate, record()).err(), Some("fenced"));
        assert_eq!(count(&p, "admitted"), 0);
        assert_eq!(count(&p, "admission_rejected"), 2);
        balanced(&p);
        p.shutdown().await;
    }
}

struct PreparationInner {
    pipeline: Arc<Pipeline>,
    held: AtomicBool,
    handler_owns: AtomicBool,
}

impl PreparationInner {
    fn release(&self) {
        if self.held.swap(false, Ordering::AcqRel) {
            self.pipeline.ledger.lock().unwrap().preparation_count -= 1;
            self.pipeline.changed.notify_one();
        }
    }
}

impl Drop for PreparationInner {
    fn drop(&mut self) {
        self.release();
    }
}

#[derive(Clone)]
pub struct PreparationLease(Arc<PreparationInner>);

impl PreparationLease {
    pub fn transfer_to_pending_work(&self) {
        self.0.handler_owns.store(false, Ordering::Release);
    }

    pub fn release_from_handler(&self) {
        if self.0.handler_owns.load(Ordering::Acquire) {
            self.release();
        }
    }

    pub fn release(&self) {
        self.0.release();
    }
}

impl Pipeline {
    pub fn new(limits: Limits, transport: Option<Arc<dyn Transport>>) -> Arc<Self> {
        assert!(limits.max_records > 0 && limits.max_bytes > 0 && limits.max_preparations > 0);
        let available = transport.is_some();
        Arc::new(Self {
            limits,
            transport,
            changed: Notify::new(),
            ledger: Mutex::new(Ledger {
                counts: Counts::default(),
                candidates: HashMap::new(),
                records: HashMap::new(),
                next_attempt: 0,
                outstanding_bytes: 0,
                preparation_count: 0,
                closing: false,
                available,
                teardown_confirmed: false,
                drain_started: None,
                drain_result: "not_started",
                http_status_counts: BTreeMap::new(),
                admission_rejection_reasons: BTreeMap::new(),
            }),
        })
    }

    pub fn prepare(self: &Arc<Self>) -> Option<PreparationLease> {
        let mut ledger = self.ledger.lock().unwrap();
        if ledger.closing || ledger.preparation_count >= self.limits.max_preparations {
            ledger.counts.preparation_rejected += 1;
            return None;
        }
        ledger.preparation_count += 1;
        Some(PreparationLease(Arc::new(PreparationInner {
            pipeline: self.clone(),
            held: AtomicBool::new(true),
            handler_owns: AtomicBool::new(true),
        })))
    }

    pub fn candidate(self: &Arc<Self>) -> Candidate {
        let mut ledger = self.ledger.lock().unwrap();
        ledger.next_attempt += 1;
        let attempt = ledger.next_attempt;
        let deadline = Instant::now() + Duration::from_millis(self.limits.http_outcome_timeout_ms);
        ledger.counts.admission_candidates += 1;
        ledger.candidates.insert(attempt, deadline);
        Candidate {
            attempt,
            deadline,
            pipeline: self.clone(),
        }
    }

    pub fn reject(&self, attempt: u64, reason: &'static str) {
        let mut ledger = self.ledger.lock().unwrap();
        if ledger.candidates.remove(&attempt).is_some() {
            ledger.counts.admission_rejected += 1;
            *ledger
                .admission_rejection_reasons
                .entry(reason)
                .or_default() += 1;
            self.changed.notify_one();
        }
    }

    pub fn serialization_failed(&self, candidate: &Candidate) {
        self.reject(candidate.attempt, "serialization");
    }

    pub fn commit(
        self: &Arc<Self>,
        candidate: Candidate,
        record: OwnedRecord,
    ) -> Result<Receipt, &'static str> {
        // Move complete owned bytes into the registry before any SDK dispatch.
        let record = Arc::new(record);
        let (outcome, receiver) = watch::channel(None);
        let mut ledger = self.ledger.lock().unwrap();
        let reason = if !ledger.candidates.contains_key(&candidate.attempt) {
            Some("fenced")
        } else if ledger.closing || !ledger.available || self.transport.is_none() {
            Some("unavailable")
        } else if Instant::now() >= candidate.deadline {
            ledger.counts.deadline_observations += 1;
            Some("deadline")
        } else if ledger.records.len() >= self.limits.max_records {
            Some("record_capacity")
        } else if record.size() > self.limits.max_bytes - ledger.outstanding_bytes {
            Some("byte_capacity")
        } else {
            None
        };
        if let Some(reason) = reason {
            drop(ledger);
            self.reject(candidate.attempt, reason);
            return Err(reason);
        }
        ledger.candidates.remove(&candidate.attempt);
        ledger.counts.admitted += 1;
        ledger.outstanding_bytes += record.size();
        ledger.records.insert(
            candidate.attempt,
            Entry {
                record: record.clone(),
                outcome,
                task: None,
            },
        );
        let pipeline = self.clone();
        let transport = self.transport.as_ref().unwrap().clone();
        let attempt = candidate.attempt;
        let task = tokio::spawn(async move {
            let succeeded = transport.dispatch(record).await;
            pipeline.settle(attempt, succeeded);
        });
        if let Some(entry) = ledger.records.get_mut(&attempt) {
            entry.task = Some(task);
        }
        drop(ledger);
        Ok(Receipt {
            attempt,
            deadline: candidate.deadline,
            outcome: receiver,
        })
    }

    pub fn settle(&self, attempt: u64, success: bool) -> bool {
        let mut ledger = self.ledger.lock().unwrap();
        if let Some(entry) = ledger.records.remove(&attempt) {
            ledger.outstanding_bytes -= entry.record.size();
            if success {
                ledger.counts.client_succeeded += 1;
            } else {
                ledger.counts.client_failed += 1;
            }
            entry.outcome.send_replace(Some(success));
            self.changed.notify_one();
            true
        } else {
            false
        }
    }

    pub async fn confirm(&self, mut receipt: Receipt) -> bool {
        if Instant::now() >= receipt.deadline {
            self.ledger.lock().unwrap().counts.deadline_observations += 1;
            return false;
        }
        let wait = async {
            loop {
                if let Some(outcome) = *receipt.outcome.borrow() {
                    return outcome;
                }
                if receipt.outcome.changed().await.is_err() {
                    return false;
                }
            }
        };
        match tokio::time::timeout_at(tokio::time::Instant::from_std(receipt.deadline), wait).await
        {
            Ok(outcome) if Instant::now() < receipt.deadline => outcome,
            Ok(_) => {
                self.ledger.lock().unwrap().counts.deadline_observations += 1;
                false
            }
            Err(_) => {
                self.ledger.lock().unwrap().counts.deadline_observations += 1;
                false
            }
        }
    }

    pub fn close_intake(&self) {
        let mut ledger = self.ledger.lock().unwrap();
        if !ledger.closing {
            ledger.closing = true;
            ledger.drain_started = Some(Instant::now());
            let rejected = ledger.candidates.len() as u64;
            ledger.counts.admission_rejected += rejected;
            *ledger
                .admission_rejection_reasons
                .entry("draining")
                .or_default() += rejected;
            ledger.candidates.clear();
            self.changed.notify_one();
        }
    }

    pub fn is_closing(&self) -> bool {
        self.ledger.lock().unwrap().closing
    }

    pub fn set_available(&self, available: bool) {
        self.ledger.lock().unwrap().available = available;
    }

    pub fn attempt_for_owned_record(&self, record: &Arc<OwnedRecord>) -> Option<u64> {
        self.ledger
            .lock()
            .unwrap()
            .records
            .iter()
            .find_map(|(attempt, entry)| Arc::ptr_eq(record, &entry.record).then_some(*attempt))
    }

    pub fn observe_admission_deadline(&self, candidate: &Candidate) -> bool {
        let mut ledger = self.ledger.lock().unwrap();
        if Instant::now() < candidate.deadline
            || ledger.candidates.remove(&candidate.attempt).is_none()
        {
            return false;
        }
        ledger.counts.deadline_observations += 1;
        ledger.counts.admission_rejected += 1;
        *ledger
            .admission_rejection_reasons
            .entry("deadline")
            .or_default() += 1;
        self.changed.notify_one();
        true
    }
    pub fn record_http_status(&self, status: u16) {
        *self
            .ledger
            .lock()
            .unwrap()
            .http_status_counts
            .entry(status)
            .or_default() += 1;
    }

    pub async fn shutdown(self: &Arc<Self>) -> serde_json::Value {
        if self.ledger.lock().unwrap().teardown_confirmed {
            return self.snapshot();
        }
        self.close_intake();
        println!("benchmark_drain_start {}", self.snapshot());
        let deadline = self.ledger.lock().unwrap().drain_started.unwrap()
            + Duration::from_millis(self.limits.drain_timeout_ms);
        loop {
            let changed = self.changed.notified();
            let settled = {
                let ledger = self.ledger.lock().unwrap();
                ledger.records.is_empty()
                    && ledger.candidates.is_empty()
                    && ledger.preparation_count == 0
            };
            if settled {
                break;
            }
            if tokio::time::timeout_at(tokio::time::Instant::from_std(deadline), changed)
                .await
                .is_err()
            {
                break;
            }
        }
        let tasks = {
            let mut ledger = self.ledger.lock().unwrap();
            ledger.drain_result = if ledger.records.is_empty()
                && ledger.preparation_count == 0
                && ledger.candidates.is_empty()
            {
                "settled"
            } else {
                "timed_out"
            };
            ledger
                .records
                .values_mut()
                .filter_map(|entry| entry.task.take())
                .collect::<Vec<_>>()
        };
        // End every dispatch task first; no cloned client can outlive teardown.
        for task in &tasks {
            task.abort();
        }
        for task in tasks {
            let _ = task.await;
        }
        let confirmed = if let Some(transport) = &self.transport {
            matches!(
                tokio::time::timeout(Duration::from_secs(5), transport.clone().teardown()).await,
                Ok(Ok(()))
            )
        } else {
            true
        };
        let mut ledger = self.ledger.lock().unwrap();
        ledger.teardown_confirmed = confirmed;
        if confirmed {
            let entries = std::mem::take(&mut ledger.records);
            ledger.counts.abandoned_unknown += entries.len() as u64;
            ledger.outstanding_bytes = 0;
            for entry in entries.into_values() {
                entry.outcome.send_replace(Some(false));
            }
        } else {
            ledger.drain_result = "teardown_unconfirmed";
        }
        drop(ledger);
        let state = self.snapshot();
        println!("benchmark_drain_end {state}");
        state
    }

    pub fn snapshot(&self) -> serde_json::Value {
        let ledger = self.ledger.lock().unwrap();
        let mut state = serde_json::to_value(&ledger.counts).unwrap();
        let object = state.as_object_mut().unwrap();
        for (name, value) in [
            (
                "admission_undecided",
                serde_json::json!(ledger.candidates.len()),
            ),
            ("outstanding", serde_json::json!(ledger.records.len())),
            (
                "outstanding_bytes",
                serde_json::json!(ledger.outstanding_bytes),
            ),
            (
                "preparation_count",
                serde_json::json!(ledger.preparation_count),
            ),
            (
                "http_status_counts",
                serde_json::json!(ledger.http_status_counts),
            ),
            (
                "admission_rejection_reasons",
                serde_json::json!(ledger.admission_rejection_reasons),
            ),
            ("closing", serde_json::json!(ledger.closing)),
            (
                "ready",
                serde_json::json!(!ledger.closing && ledger.available && self.transport.is_some()),
            ),
            (
                "teardown_confirmed",
                serde_json::json!(ledger.teardown_confirmed),
            ),
            ("drain_result", serde_json::json!(ledger.drain_result)),
            ("configured_bounds", serde_json::json!(self.limits)),
            ("effective_bounds", serde_json::json!(self.limits)),
            ("scope", serde_json::json!("container_process")),
        ] {
            object.insert(name.to_string(), value);
        }
        state
    }
}
