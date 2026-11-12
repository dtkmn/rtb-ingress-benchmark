//! Opt-in test transport around the actual owned pipeline; no Kafka proof.
use crate::pipeline::{
    Candidate, DispatchFuture, OwnedRecord, Pipeline, PreparationLease, Receipt, TeardownFuture,
    Transport,
};
use crate::DeliveryMode;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::io;
use std::sync::{Arc, Mutex, Weak};
use std::time::{Duration, Instant};
use tokio::sync::watch;

const FAULTS: &[&str] = &[
    "serialization_failure",
    "producer_unavailable",
    "admission_throw",
    "admission_failed_future",
    "held_admission_then_success",
    "held_delivery_then_success",
    "delivery_failure_after_receipt",
    "response_deadline_then_late_success",
    "backlog_record_limit",
    "backlog_byte_limit",
    "preparation_limit",
    "final_slot_race",
    "drain_deadline_unsettled",
    "confirm_ack_zero",
    "held_admission_then_rejection",
    "admission_deadline_then_late_receipt",
    "duplicate_completion_callback",
    "source_mutation_after_receipt",
];

pub fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut text = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let value = (u32::from(chunk[0]) << 16)
            | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
            | u32::from(*chunk.get(2).unwrap_or(&0));
        text.push(ALPHABET[((value >> 18) & 63) as usize] as char);
        text.push(ALPHABET[((value >> 12) & 63) as usize] as char);
        text.push(if chunk.len() > 1 {
            ALPHABET[((value >> 6) & 63) as usize] as char
        } else {
            '='
        });
        text.push(if chunk.len() > 2 {
            ALPHABET[(value & 63) as usize] as char
        } else {
            '='
        });
    }
    text
}

fn record_view(record: &OwnedRecord) -> Value {
    json!({"topic": record.topic, "key_base64": base64(&record.key), "value_base64": base64(&record.value)})
}

async fn wait_gate(mut gate: watch::Receiver<Option<bool>>) -> bool {
    loop {
        if let Some(outcome) = *gate.borrow() {
            return outcome;
        }
        if gate.changed().await.is_err() {
            return false;
        }
    }
}

struct CallerStorage {
    before: Value,
    slot: OwnedRecord,
}
struct CaseState {
    case_id: Option<String>,
    fault: String,
    events: Vec<Value>,
    records: Vec<Value>,
    admission: watch::Sender<Option<bool>>,
    delivery: watch::Sender<Option<bool>>,
    setup_delivery: watch::Sender<Option<bool>>,
    held_admission: HashSet<u64>,
    held_delivery: HashMap<u64, String>,
    receipts: HashMap<u64, String>,
    setup_attempts: HashSet<u64>,
    leases: Vec<PreparationLease>,
    sources: HashMap<u64, CallerStorage>,
    late: Vec<(Candidate, OwnedRecord, PreparationLease)>,
    deadlines: HashMap<u64, Instant>,
    baseline: Value,
    setup: Value,
    mutation: Value,
    drain: Value,
    closed: bool,
}

impl CaseState {
    fn empty() -> Self {
        Self {
            case_id: None,
            fault: String::new(),
            events: Vec::new(),
            records: Vec::new(),
            admission: watch::channel(None).0,
            delivery: watch::channel(None).0,
            setup_delivery: watch::channel(None).0,
            held_admission: HashSet::new(),
            held_delivery: HashMap::new(),
            receipts: HashMap::new(),
            setup_attempts: HashSet::new(),
            leases: Vec::new(),
            sources: HashMap::new(),
            late: Vec::new(),
            deadlines: HashMap::new(),
            baseline: Value::Null,
            setup: json!({"records":0,"bytes":0,"preparations":0}),
            mutation: Value::Null,
            drain: Value::Null,
            closed: false,
        }
    }
    fn event(&mut self, name: &str, details: Value) {
        if self.events.len() >= 256 {
            return;
        } // only bounded milestones; fixture contenders are independently capped at two
        let mut event = details.as_object().cloned().unwrap_or_default();
        event.insert("sequence".into(), json!(self.events.len() + 1));
        event.insert("name".into(), json!(name));
        self.events.push(Value::Object(event));
    }
}

pub struct Controller {
    token: String,
    mode: DeliveryMode,
    topic: String,
    pipeline: Mutex<Weak<Pipeline>>,
    inner: Mutex<CaseState>,
}

struct AdmissionHold<'a> {
    controller: &'a Controller,
    attempt: u64,
}
impl Drop for AdmissionHold<'_> {
    fn drop(&mut self) {
        self.controller
            .inner
            .lock()
            .unwrap()
            .held_admission
            .remove(&self.attempt);
    }
}

impl Controller {
    pub fn from_env(
        mode: DeliveryMode,
        workers: usize,
        topic: String,
    ) -> io::Result<Option<Arc<Self>>> {
        if std::env::var("BENCHMARK_CONFORMANCE").as_deref() != Ok("1") {
            return Ok(None);
        }
        let token = std::env::var("BENCHMARK_CONFORMANCE_TOKEN").unwrap_or_default();
        if token.is_empty() || token.chars().count() > 256 || workers != 1 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "conformance requires a nonempty bounded token and exactly one serving worker",
            ));
        }
        Ok(Some(Self::new(mode, topic, token)))
    }
    pub fn new(mode: DeliveryMode, topic: String, token: String) -> Arc<Self> {
        Arc::new(Self {
            token,
            mode,
            topic,
            pipeline: Mutex::new(Weak::new()),
            inner: Mutex::new(CaseState::empty()),
        })
    }
    pub fn attach(&self, pipeline: &Arc<Pipeline>) {
        *self.pipeline.lock().unwrap() = Arc::downgrade(pipeline);
    }
    fn pipeline(&self) -> Arc<Pipeline> {
        self.pipeline
            .lock()
            .unwrap()
            .upgrade()
            .expect("serving pipeline exists")
    }
    pub fn authorized(&self, token: &str) -> bool {
        let supplied = token.as_bytes();
        let expected = self.token.as_bytes();
        if supplied.len() != expected.len() {
            return false;
        }
        supplied
            .iter()
            .zip(expected)
            .fold(0u8, |difference, (a, b)| difference | (a ^ b))
            == 0
    }
    pub fn serialization_failure(&self) -> bool {
        let mut state = self.inner.lock().unwrap();
        if state.fault == "serialization_failure" {
            state.event("serialization_failure", json!({}));
            true
        } else {
            false
        }
    }

    pub async fn admit(
        &self,
        candidate: Candidate,
        mut record: OwnedRecord,
        preparation: PreparationLease,
    ) -> Result<Receipt, &'static str> {
        let pipeline = self.pipeline();
        let attempt = candidate.attempt();
        let deadline = candidate.deadline();
        let key = String::from_utf8(record.key.clone()).expect("validated scalar UTF-8 key");
        let (fault, gate) = {
            let mut state = self.inner.lock().unwrap();
            if state.receipts.len() + state.held_admission.len() >= 2 {
                pipeline.reject(attempt, "fixture_bound");
                return Err("fixture_bound");
            }
            state.deadlines.insert(attempt, deadline);
            if state.fault == "source_mutation_after_receipt" {
                let before = record_view(&record);
                // Move the real prepared bytes out of their caller slot. Retain that exact emptied slot for reuse.
                let owned = std::mem::replace(
                    &mut record,
                    OwnedRecord {
                        topic: String::new(),
                        key: Vec::new(),
                        value: Vec::new(),
                    },
                );
                state.sources.insert(
                    attempt,
                    CallerStorage {
                        before,
                        slot: record,
                    },
                );
                record = owned;
            }
            let fault = state.fault.clone();
            let gate = state.admission.subscribe();
            if matches!(
                fault.as_str(),
                "held_admission_then_success"
                    | "held_admission_then_rejection"
                    | "admission_deadline_then_late_receipt"
                    | "final_slot_race"
            ) {
                state.held_admission.insert(attempt);
                state.event("admission_held", json!({"attempt":attempt,"key":key}));
            }
            (fault, gate)
        };
        if matches!(
            fault.as_str(),
            "admission_throw" | "admission_failed_future"
        ) {
            pipeline.reject(attempt, "injected_admission_rejection");
            self.inner
                .lock()
                .unwrap()
                .event("admission_rejected", json!({"key":key,"attempt":attempt}));
            // A Result-returning boundary and an awaited failed future both precede any receipt.
            if fault == "admission_failed_future" {
                return std::future::ready(Err("injected_failed_future")).await;
            }
            return Err("injected_admission_rejection");
        }
        if matches!(
            fault.as_str(),
            "held_admission_then_success"
                | "held_admission_then_rejection"
                | "admission_deadline_then_late_receipt"
                | "final_slot_race"
        ) {
            let _hold = AdmissionHold {
                controller: self,
                attempt,
            };
            let outcome =
                tokio::time::timeout_at(tokio::time::Instant::from_std(deadline), wait_gate(gate))
                    .await;
            self.inner.lock().unwrap().held_admission.remove(&attempt);
            match outcome {
                Err(_) => {
                    if !pipeline.observe_admission_deadline(&candidate) {
                        return Err("admission_deadline_not_observed");
                    }
                    let mut state = self.inner.lock().unwrap();
                    state.event("deadline_observed", json!({"key":key,"attempt":attempt}));
                    preparation.transfer_to_pending_work();
                    state.late.push((candidate, record, preparation));
                    return Err("admission_deadline");
                }
                Ok(false) => {
                    pipeline.reject(attempt, "deferred_rejection");
                    self.inner
                        .lock()
                        .unwrap()
                        .event("admission_rejected", json!({"key":key,"attempt":attempt}));
                    return Err("deferred_rejection");
                }
                Ok(true) => {}
            }
        }
        match pipeline.commit(candidate, record) {
            Ok(receipt) => {
                let mut state = self.inner.lock().unwrap();
                state.receipts.insert(attempt, key.clone());
                state.event("receipt_committed", json!({"key":key,"attempt":attempt}));
                Ok(receipt)
            }
            Err(reason) => {
                self.inner.lock().unwrap().event(
                    "admission_rejected",
                    json!({"key":key,"attempt":attempt,"reason":reason}),
                );
                Err(reason)
            }
        }
    }

    async fn configure(&self, case_id: &str, fault: &str) -> Result<(), String> {
        if !FAULTS.contains(&fault) || case_id.is_empty() || case_id.len() > 128 {
            return Err("unknown fault or invalid case ID".into());
        }
        let pipeline = self.pipeline();
        let actual = pipeline.snapshot();
        if actual["closing"] == true
            || actual["outstanding"] != 0
            || actual["admission_undecided"] != 0
            || actual["preparation_count"] != 0
        {
            return Err("previous live work must settle through cleanup".into());
        }
        let limits = &actual["effective_bounds"];
        if limits["max_records"] != 1024
            || limits["max_bytes"] != 16777216
            || limits["max_preparations"] != 128
        {
            return Err(
                "reference capacities must remain 1024 records/16 MiB/128 preparations".into(),
            );
        }
        {
            let mut state = self.inner.lock().unwrap();
            if state.closed {
                return Err("transport already torn down".into());
            }
            *state = CaseState::empty();
            state.case_id = Some(case_id.into());
            state.fault = fault.into();
        }
        if fault == "preparation_limit" {
            let mut leases = Vec::with_capacity(128);
            for _ in 0..128 {
                leases.push(pipeline.prepare().ok_or("preparation setup failed")?);
            }
            let mut state = self.inner.lock().unwrap();
            state.leases = leases;
            state.setup["preparations"] = json!(128);
        } else if self.mode.uses_kafka() {
            pipeline.set_available(fault != "producer_unavailable");
            let count = if fault == "backlog_record_limit" {
                1024
            } else if fault == "final_slot_race" {
                1023
            } else {
                0
            };
            let mut remaining = if fault == "backlog_byte_limit" {
                16777215usize
            } else {
                0
            };
            let mut index = 0;
            while index < count || remaining > 0 {
                let key = format!("__setup__{index}").into_bytes();
                let size = if remaining > 0 {
                    remaining.min(65536)
                } else {
                    key.len() + 1
                };
                if size <= key.len() {
                    return Err("setup key exceeds byte chunk".into());
                }
                let record = OwnedRecord {
                    topic: self.topic.clone(),
                    value: vec![b'x'; size - key.len()],
                    key,
                };
                let receipt = pipeline
                    .commit(pipeline.candidate(), record)
                    .map_err(str::to_string)?;
                let mut state = self.inner.lock().unwrap();
                state.setup_attempts.insert(receipt.attempt);
                state.setup["records"] = json!(state.setup["records"].as_u64().unwrap() + 1);
                state.setup["bytes"] = json!(state.setup["bytes"].as_u64().unwrap() + size as u64);
                remaining = remaining.saturating_sub(size);
                index += 1;
            }
        }
        self.inner.lock().unwrap().baseline = pipeline.snapshot();
        Ok(())
    }

    fn release_admission(&self, accepted: bool, enforce_race: bool) -> Result<(), String> {
        let late = {
            let mut state = self.inner.lock().unwrap();
            if enforce_race && state.fault == "final_slot_race" && state.held_admission.len() != 2 {
                return Err("race requires two observed real candidates".into());
            }
            state.admission.send_replace(Some(accepted));
            state.event(
                if accepted {
                    "admission_released"
                } else {
                    "admission_rejected"
                },
                json!({}),
            );
            std::mem::take(&mut state.late)
        };
        let pipeline = self.pipeline();
        for (candidate, record, preparation) in late {
            let attempt = candidate.attempt();
            let key = String::from_utf8(record.key.clone()).unwrap();
            let reason = pipeline
                .commit(candidate, record)
                .err()
                .ok_or("late candidate escaped actual commit fence")?;
            self.inner.lock().unwrap().event(
                "late_receipt_fenced",
                json!({"attempt":attempt,"key":key,"reason":reason}),
            );
            preparation.release();
        }
        Ok(())
    }

    fn release_delivery(&self, success: bool) -> Result<(), String> {
        let mut state = self.inner.lock().unwrap();
        if state.closed {
            return Err("transport already torn down".into());
        }
        state.delivery.send_replace(Some(success));
        state.event("delivery_released", json!({"succeeded":success}));
        Ok(())
    }

    async fn cleanup(&self) -> Result<(), String> {
        let pipeline = self.pipeline();
        if pipeline.is_closing() {
            return Ok(());
        }
        self.release_admission(true, false)?;
        self.release_delivery(true)?;
        {
            let mut state = self.inner.lock().unwrap();
            state.setup_delivery.send_replace(Some(true));
            for lease in state.leases.drain(..) {
                lease.release();
            }
        }
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let state = pipeline.snapshot();
                if state["outstanding"] == 0
                    && state["admission_undecided"] == 0
                    && state["preparation_count"] == 0
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .map_err(|_| "cleanup did not settle actual work")?;
        pipeline.set_available(self.mode.uses_kafka());
        Ok(())
    }

    pub async fn command(&self, command: Value) -> Result<Value, String> {
        let action = command["action"].as_str().ok_or("missing action")?;
        if !self.mode.uses_kafka()
            && matches!(
                action,
                "release_admission"
                    | "reject_admission"
                    | "release_delivery"
                    | "fail_delivery"
                    | "mutate_source"
                    | "duplicate_completion"
                    | "drain"
            )
        {
            return Ok(self.snapshot());
        }
        match action {
            "configure" => {
                self.configure(
                    command["case_id"].as_str().ok_or("missing case ID")?,
                    command["fault"].as_str().ok_or("missing fault")?,
                )
                .await?
            }
            "release_admission" => self.release_admission(true, true)?,
            "reject_admission" => self.release_admission(false, true)?,
            "release_delivery" => self.release_delivery(true)?,
            "fail_delivery" => self.release_delivery(false)?,
            "cleanup" => self.cleanup().await?,
            "mutate_source" => {
                let mut state = self.inner.lock().unwrap();
                if state.sources.is_empty() {
                    return Err("no moved caller storage retained".into());
                }
                let mut mutations = Vec::new();
                for source in state.sources.values_mut() {
                    source.slot.topic = "mutated-caller-topic".into();
                    source.slot.key = b"mutated-caller-key".to_vec();
                    source.slot.value = b"mutated-caller-value".to_vec();
                    mutations
                        .push(json!({"before":source.before,"after":record_view(&source.slot)}));
                }
                state.mutation = json!({"performed":true,"evidence":"real prepared Vec buffers moved from caller record slot into pipeline before receipt; actual emptied caller slot reused before transport dispatch; Rust ownership prevents aliasing",
                    "before_value_base64":mutations[0]["before"]["value_base64"],"after_value_base64":mutations[0]["after"]["value_base64"],"sources":mutations});
                state.event("source_mutated", json!({}));
            }
            "duplicate_completion" => {
                let receipts: Vec<_> = self
                    .inner
                    .lock()
                    .unwrap()
                    .receipts
                    .iter()
                    .map(|(attempt, key)| (*attempt, key.clone()))
                    .collect();
                if receipts.is_empty() {
                    return Err("no fixture receipt".into());
                }
                let pipeline = self.pipeline();
                for (attempt, key) in receipts {
                    let before = pipeline.snapshot();
                    if before["outstanding"].as_u64().unwrap()
                        > self.inner.lock().unwrap().setup_attempts.len() as u64
                    {
                        return Err("first client outcome must settle before duplicate".into());
                    }
                    let applied = pipeline.settle(attempt, true);
                    let after = pipeline.snapshot();
                    self.inner.lock().unwrap().event("duplicate_completion",json!({"key":key,"attempt":attempt,"applied":applied,"before":before,"after":after}));
                }
            }
            "drain" => {
                let pipeline = self.pipeline();
                let before = pipeline.snapshot();
                pipeline.close_intake();
                self.inner
                    .lock()
                    .unwrap()
                    .event("drain_started", json!({"state":pipeline.snapshot()}));
                let after = pipeline.shutdown().await;
                self.inner.lock().unwrap().drain = json!({"before":before,"after":after});
            }
            _ => return Err("unknown command".into()),
        }
        Ok(self.snapshot())
    }

    pub fn snapshot(&self) -> Value {
        let state = self.inner.lock().unwrap();
        json!({"enabled":true,"case_id":state.case_id,"fault":state.fault,"events":state.events,"records":state.records,
            "baseline":state.baseline,"state":self.pipeline().snapshot(),"held_admission":state.held_admission.len(),"held_delivery":state.held_delivery.len(),
            "deadline_elapsed":!state.deadlines.is_empty() && state.deadlines.values().all(|deadline|Instant::now()>=*deadline),
            "setup":state.setup,"mutation":state.mutation,"drain":state.drain})
    }
}

impl Transport for Controller {
    fn dispatch(self: Arc<Self>, record: Arc<OwnedRecord>) -> DispatchFuture {
        Box::pin(async move {
            let pipeline = self.pipeline();
            let Some(attempt) = pipeline.attempt_for_owned_record(&record) else {
                return false;
            };
            let (setup, held, source, gate) = {
                let mut state = self.inner.lock().unwrap();
                let setup = state.setup_attempts.contains(&attempt);
                let held = matches!(
                    state.fault.as_str(),
                    "held_admission_then_success"
                        | "held_delivery_then_success"
                        | "delivery_failure_after_receipt"
                        | "response_deadline_then_late_success"
                        | "final_slot_race"
                        | "drain_deadline_unsettled"
                        | "duplicate_completion_callback"
                        | "source_mutation_after_receipt"
                );
                let source = state.fault == "source_mutation_after_receipt";
                let key = String::from_utf8(record.key.clone()).unwrap();
                if !setup && held {
                    state.held_delivery.insert(attempt, key.clone());
                    state.event("delivery_held", json!({"key":key,"attempt":attempt}));
                }
                if !setup && !source {
                    state.records.push(record_view(&record));
                }
                let gate = if setup {
                    state.setup_delivery.subscribe()
                } else {
                    state.delivery.subscribe()
                };
                (setup, held, source, gate)
            };
            let success = if setup || held {
                wait_gate(gate).await
            } else {
                true
            };
            let mut state = self.inner.lock().unwrap();
            if state.closed {
                return false;
            }
            if !setup {
                if source {
                    state.records.push(record_view(&record));
                }
                state.held_delivery.remove(&attempt);
                state.event(
                    if success {
                        "client_succeeded"
                    } else {
                        "client_failed"
                    },
                    json!({"key":String::from_utf8(record.key.clone()).unwrap(),"attempt":attempt}),
                );
            }
            success
        })
    }
    fn teardown(self: Arc<Self>) -> TeardownFuture {
        Box::pin(async move {
            let pipeline = self.pipeline();
            let has_late = {
                let mut state = self.inner.lock().unwrap();
                state.closed = true;
                !state.late.is_empty()
            };
            if has_late {
                self.release_admission(false, false)?;
            }
            let mut state = self.inner.lock().unwrap();
            state.event("teardown_confirmed", json!({"state":pipeline.snapshot()}));
            Ok(())
        })
    }
}
