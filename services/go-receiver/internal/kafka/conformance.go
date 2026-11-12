package kafka

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"sync"
	"time"
)

var conformanceFaults = map[string]bool{
	"serialization_failure": true, "producer_unavailable": true, "admission_throw": true,
	"admission_failed_future": true, "held_admission_then_success": true, "held_delivery_then_success": true,
	"delivery_failure_after_receipt": true, "response_deadline_then_late_success": true,
	"backlog_record_limit": true, "backlog_byte_limit": true, "preparation_limit": true,
	"final_slot_race": true, "drain_deadline_unsettled": true, "confirm_ack_zero": true,
	"held_admission_then_rejection": true, "admission_deadline_then_late_receipt": true,
	"duplicate_completion_callback": true, "source_mutation_after_receipt": true,
}

type ConformanceCommand struct {
	Action string `json:"action"`
	CaseID string `json:"case_id"`
	Fault  string `json:"fault"`
}
type conformanceFixture struct {
	key      string
	deadline time.Time
	source   *Record
}
type conformanceAdmission struct {
	candidate *Candidate
	record    *Record
	gate      <-chan struct{}
	release   func()
	timedOut  bool
}
type conformanceDelivery struct {
	record   Record
	complete func(error)
	setup    bool
}

// This optional transport records actual pipeline milestones, not counters or
// responses. Normal production uses kafkaDispatcher and allocates no ledger.
type Conformance struct {
	opMu                               sync.Mutex
	mu                                 sync.Mutex
	pipeline                           *Pipeline
	topic, token, caseID, fault        string
	closed, configuring                bool
	events                             []map[string]any
	records                            []map[string]any
	baseline                           State
	fixture                            map[uint64]*conformanceFixture
	sources                            map[uint64]*Record
	setupIDs                           map[uint64]bool
	admissions                         map[uint64]*conformanceAdmission
	deliveries                         map[uint64]*conformanceDelivery
	completed                          map[uint64]func(error)
	preparations                       []func()
	setup                              map[string]any
	mutation, drain                    map[string]any
	admissionGate                      chan struct{}
	admissionReleased, admissionAccept bool
	callbacks                          sync.WaitGroup
}

func NewConformance(mode string, limits Limits, topic, token string) (*Conformance, *Pipeline) {
	a := &Conformance{topic: topic, token: token}
	p := NewPipeline(mode, limits, a)
	a.pipeline = p
	p.conformance = a
	a.reset(p.Snapshot())
	return a, p
}
func (a *Conformance) Authorized(token string) bool {
	return token != "" && subtle.ConstantTimeCompare([]byte(token), []byte(a.token)) == 1
}
func (a *Conformance) reset(baseline State) {
	a.caseID, a.fault = "", ""
	a.events, a.records = nil, nil
	a.baseline = baseline
	a.fixture = make(map[uint64]*conformanceFixture)
	a.sources = make(map[uint64]*Record)
	a.setupIDs = make(map[uint64]bool)
	a.admissions = make(map[uint64]*conformanceAdmission)
	a.deliveries = make(map[uint64]*conformanceDelivery)
	a.completed = make(map[uint64]func(error))
	a.preparations = nil
	a.setup = map[string]any{"records": 0, "bytes": int64(0), "preparations": 0}
	a.mutation, a.drain = nil, nil
	a.admissionGate = make(chan struct{})
	a.admissionReleased, a.admissionAccept = false, false
}
func (a *Conformance) noteLocked(name string, id uint64) {
	if len(a.events) >= 256 {
		return
	}
	event := map[string]any{"sequence": len(a.events) + 1, "name": name}
	if fixture := a.fixture[id]; fixture != nil {
		event["key"] = fixture.key
	}
	a.events = append(a.events, event)
}
func (a *Conformance) SerializationCheck() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.fault == "serialization_failure" {
		return errors.New("injected serialization boundary failure")
	}
	return nil
}
func (a *Conformance) committed(receipt *Receipt, deadline time.Time, record Record) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.configuring {
		a.setupIDs[receipt.AttemptID] = true
		return
	}
	fixture := a.fixture[receipt.AttemptID]
	if fixture == nil {
		fixture = &conformanceFixture{key: string(record.Key), deadline: deadline}
		a.fixture[receipt.AttemptID] = fixture
	}
	fixture.source = a.sources[receipt.AttemptID]
	delete(a.sources, receipt.AttemptID)
	a.noteLocked("receipt_committed", receipt.AttemptID)
}
func (a *Conformance) outcome(id uint64, err error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.setupIDs[id] {
		return
	}
	if a.fixture[id] != nil {
		name := "client_succeeded"
		if err != nil {
			name = "client_failed"
		}
		a.noteLocked(name, id)
	}
}

func (a *Conformance) Admit(candidate *Candidate, record *Record, retainPreparation func() func()) (*Receipt, error) {
	a.mu.Lock()
	if len(a.fixture) >= 2 {
		a.mu.Unlock()
		candidate.Reject("fixture_bound")
		return nil, ErrCapacity
	}
	fault := a.fault
	a.fixture[candidate.id] = &conformanceFixture{key: string(record.Key), deadline: candidate.deadline}
	if fault == "source_mutation_after_receipt" {
		a.sources[candidate.id] = record
	}
	a.mu.Unlock()
	if fault == "producer_unavailable" || fault == "admission_throw" || fault == "admission_failed_future" {
		var err error
		if fault == "admission_throw" {
			func() {
				defer func() {
					if recover() != nil {
						err = ErrUnavailable
					}
				}()
				panic("injected admission throw")
			}()
		} else if fault == "admission_failed_future" {
			failed := make(chan error, 1)
			go func() { failed <- ErrUnavailable }()
			err = <-failed
		} else {
			err = ErrUnavailable
		}
		candidate.Reject(fault)
		a.mu.Lock()
		a.noteLocked("admission_rejected", candidate.id)
		a.mu.Unlock()
		return nil, err
	}
	hold := fault == "held_admission_then_success" || fault == "held_admission_then_rejection" || fault == "admission_deadline_then_late_receipt" || fault == "final_slot_race"
	if !hold {
		receipt, err := candidate.Commit(*record)
		if err != nil {
			a.mu.Lock()
			a.noteLocked("admission_rejected", candidate.id)
			a.mu.Unlock()
		}
		return receipt, err
	}
	a.mu.Lock()
	gate := a.admissionGate
	a.mu.Unlock()
	held := &conformanceAdmission{candidate: candidate, record: record, gate: gate, release: retainPreparation()}
	a.mu.Lock()
	a.admissions[candidate.id] = held
	a.noteLocked("admission_held", candidate.id)
	a.mu.Unlock()
	timer := time.NewTimer(time.Until(candidate.deadline))
	defer timer.Stop()
	select {
	case <-held.gate:
		a.mu.Lock()
		accept := a.admissionAccept
		a.mu.Unlock()
		defer held.release()
		defer func() { a.mu.Lock(); delete(a.admissions, candidate.id); a.mu.Unlock() }()
		if !accept {
			candidate.Reject("deferred_admission_rejected")
			a.mu.Lock()
			a.noteLocked("admission_rejected", candidate.id)
			a.mu.Unlock()
			return nil, ErrUnavailable
		}
		receipt, err := candidate.Commit(*record)
		if err != nil {
			a.mu.Lock()
			a.noteLocked("admission_rejected", candidate.id)
			a.mu.Unlock()
		}
		return receipt, err
	case <-timer.C:
		_, err := candidate.Commit(*record) // Real deadline fence and counter.
		a.mu.Lock()
		held.timedOut = true
		a.noteLocked("deadline_observed", candidate.id)
		a.mu.Unlock()
		if err == nil {
			return nil, errors.New("expired admission unexpectedly committed")
		}
		// The actual preparation lease stays with held work until release.
		return nil, err
	}
}

func encodedRecord(record Record) map[string]any {
	return map[string]any{"topic": record.Topic, "key_base64": base64.StdEncoding.EncodeToString(record.Key), "value_base64": base64.StdEncoding.EncodeToString(record.Value)}
}
func (a *Conformance) captureLocked(record Record) {
	if len(a.records) < 16 {
		a.records = append(a.records, encodedRecord(record))
	}
}
func (a *Conformance) Dispatch(_ context.Context, record Record, complete func(error)) {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	setup := a.setupIDs[record.attemptID]
	fault := a.fault
	if !setup && fault != "source_mutation_after_receipt" {
		a.captureLocked(record)
	}
	hold := setup || fault == "held_admission_then_success" || fault == "held_delivery_then_success" || fault == "delivery_failure_after_receipt" || fault == "response_deadline_then_late_success" || fault == "drain_deadline_unsettled" || fault == "final_slot_race" || fault == "duplicate_completion_callback" || fault == "source_mutation_after_receipt"
	if hold {
		a.deliveries[record.attemptID] = &conformanceDelivery{record: record, complete: complete, setup: setup}
		if !setup {
			a.noteLocked("delivery_held", record.attemptID)
		}
		a.mu.Unlock()
		return
	}
	a.completed[record.attemptID] = complete
	a.callbacks.Add(1)
	a.mu.Unlock()
	defer a.callbacks.Done()
	complete(nil)
}
func (a *Conformance) Close(ctx context.Context) error {
	a.mu.Lock()
	a.closed = true
	a.deliveries = make(map[uint64]*conformanceDelivery)
	a.completed = make(map[uint64]func(error))
	a.mu.Unlock()
	done := make(chan struct{})
	go func() { a.pipeline.dispatches.Wait(); a.callbacks.Wait(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
		return ctx.Err()
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.noteLocked("teardown_confirmed", 0)
	return nil
}

func (a *Conformance) configure(command ConformanceCommand) error {
	if !conformanceFaults[command.Fault] || command.CaseID == "" || len(command.CaseID) > 128 {
		return errors.New("unknown fault or invalid case_id")
	}
	state := a.pipeline.Snapshot()
	if state.IntakeClosed || state.Outstanding != 0 || state.PreparationCount != 0 || state.AdmissionUndecided != 0 {
		return errors.New("cleanup actual live work before configure")
	}
	a.mu.Lock()
	a.reset(state)
	a.caseID, a.fault = command.CaseID, command.Fault
	a.configuring = true
	a.mu.Unlock()
	defer func() { a.mu.Lock(); a.configuring = false; a.mu.Unlock() }()
	limits := state.Limits
	if limits.MaxRecords != 1024 || limits.MaxBytes != 16777216 || limits.MaxPreparation != 128 {
		return errors.New("conformance reference capacities must remain 1024 records/16 MiB/128 preparations")
	}
	if state.DeliveryMode != DeliveryModeHttpOnly && (command.Fault == "backlog_record_limit" || command.Fault == "backlog_byte_limit" || command.Fault == "final_slot_race") {
		count := limits.MaxRecords
		if command.Fault == "final_slot_race" {
			count--
		}
		remaining := int64(0)
		if command.Fault == "backlog_byte_limit" {
			remaining = limits.MaxBytes - 1
		}
		for index := 0; ; index++ {
			if command.Fault == "backlog_byte_limit" {
				if remaining <= 0 {
					break
				}
			} else if index >= count {
				break
			}
			key := []byte(fmt.Sprintf("__conformance_setup_%d", index))
			size := int64(len(key) + 1)
			if command.Fault == "backlog_byte_limit" {
				size = min(65536, remaining)
			}
			if size < int64(len(key)) {
				return errors.New("byte setup chunk cannot fit key")
			}
			_, err := a.pipeline.BeginCandidate(context.Background()).Commit(Record{Topic: a.topic, Key: key, Value: make([]byte, int(size)-len(key))})
			if err != nil {
				return err
			}
			a.mu.Lock()
			a.setup["records"] = a.setup["records"].(int) + 1
			a.setup["bytes"] = a.setup["bytes"].(int64) + size
			a.mu.Unlock()
			remaining -= size
		}
	} else if command.Fault == "preparation_limit" {
		for index := 0; index < limits.MaxPreparation; index++ {
			release, err := a.pipeline.AcquirePreparation()
			if err != nil {
				return err
			}
			a.mu.Lock()
			a.preparations = append(a.preparations, release)
			a.setup["preparations"] = a.setup["preparations"].(int) + 1
			a.mu.Unlock()
		}
	}
	// Every setup receipt is committed synchronously; wait for its real dispatch
	// registration before exposing the setup baseline to the driver.
	if err := a.waitDispatchSetup(); err != nil {
		return err
	}
	a.mu.Lock()
	a.baseline = a.pipeline.Snapshot()
	a.mu.Unlock()
	return nil
}
func (a *Conformance) waitDispatchSetup() error {
	deadline := time.Now().Add(a.pipeline.HTTPTimeout())
	for {
		a.mu.Lock()
		ready := len(a.deliveries) >= len(a.setupIDs)
		a.mu.Unlock()
		if ready {
			return nil
		}
		if !time.Now().Before(deadline) {
			return errors.New("setup dispatch registration deadline")
		}
		time.Sleep(time.Millisecond)
	}
}
func (a *Conformance) releaseAdmission(reject, cleanup bool) error {
	a.mu.Lock()
	if !cleanup && a.fault == "final_slot_race" && len(a.admissions) != 2 {
		a.mu.Unlock()
		return errors.New("race requires two held actual contenders")
	}
	held := make(map[uint64]*conformanceAdmission, len(a.admissions))
	for id, value := range a.admissions {
		held[id] = value
		a.noteLocked("admission_released", id)
	}
	if !a.admissionReleased {
		a.admissionAccept = !reject
		a.admissionReleased = true
		close(a.admissionGate) // One shared release wakes every real contender.
	}
	a.mu.Unlock()
	for id, value := range held {
		a.mu.Lock()
		timedOut := value.timedOut
		a.mu.Unlock()
		if timedOut {
			if _, err := value.candidate.Commit(*value.record); err == nil {
				return errors.New("late receipt unexpectedly committed")
			}
			a.mu.Lock()
			delete(a.admissions, id)
			a.noteLocked("late_receipt_fenced", id)
			a.mu.Unlock()
			value.release()
		}
	}
	return nil
}
func (a *Conformance) releaseDelivery(failed, includeSetup bool) {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	held := make(map[uint64]*conformanceDelivery)
	for id, value := range a.deliveries {
		if value.setup && !includeSetup {
			continue
		}
		held[id] = value
		delete(a.deliveries, id)
		if !value.setup {
			if a.fault == "source_mutation_after_receipt" {
				a.captureLocked(value.record)
			}
			a.completed[id] = value.complete
			a.noteLocked("delivery_released", id)
		}
	}
	a.callbacks.Add(len(held))
	a.mu.Unlock()
	for _, value := range held {
		var err error
		if failed {
			err = ErrUnavailable
		}
		value.complete(err)
		a.callbacks.Done()
	}
}
func (a *Conformance) mutateSource() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	for id, fixture := range a.fixture {
		if fixture.source == nil {
			continue
		}
		source := fixture.source
		before := base64.StdEncoding.EncodeToString(source.Value)
		for index := range source.Value {
			source.Value[index] = 'Z'
		}
		for index := range source.Key {
			source.Key[index] = 'X'
		}
		source.Topic += "-caller-reused"
		a.mutation = map[string]any{"performed": true, "evidence": "Mutated the original handler serialization/key slices and rebound caller topic after the real pipeline copied commitment; dispatch retains original owned bytes", "before_value_base64": before, "after_value_base64": base64.StdEncoding.EncodeToString(source.Value)}
		a.noteLocked("source_mutated", id)
		return nil
	}
	return errors.New("no actual caller buffer retained for mutation")
}
func (a *Conformance) CallerReleased(id uint64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.events) >= 256 {
		return
	}
	a.noteLocked("caller_released", id)
	if len(a.events) > 0 {
		a.events[len(a.events)-1]["evidence"] = "Preparation helper returned, releasing model/value/source-record locals; handler cleared body references and returned the actual preparation lease before confirm. Original source slices retained only for the explicit mutation schedule; no GC claim."
	}
}
func (a *Conformance) Command(command ConformanceCommand) (map[string]any, error) {
	a.opMu.Lock()
	defer a.opMu.Unlock()
	a.mu.Lock()
	closed := a.closed
	a.mu.Unlock()
	if closed {
		return nil, errors.New("conformance transport closed")
	}
	switch command.Action {
	case "configure":
		if err := a.configure(command); err != nil {
			return nil, err
		}
	case "release_admission":
		if err := a.releaseAdmission(false, false); err != nil {
			return nil, err
		}
	case "reject_admission":
		if err := a.releaseAdmission(true, false); err != nil {
			return nil, err
		}
	case "release_delivery":
		a.releaseDelivery(false, false)
	case "fail_delivery":
		a.releaseDelivery(true, false)
	case "mutate_source":
		if err := a.mutateSource(); err != nil {
			return nil, err
		}
	case "duplicate_completion":
		a.mu.Lock()
		if a.closed {
			a.mu.Unlock()
			return nil, errors.New("conformance transport closed")
		}
		callbacks := make(map[uint64]func(error))
		for id, complete := range a.completed {
			callbacks[id] = complete
		}
		a.callbacks.Add(len(callbacks))
		a.mu.Unlock()
		if len(callbacks) == 0 {
			return nil, errors.New("first terminal callback must precede duplicate")
		}
		for id, complete := range callbacks {
			complete(nil)
			a.callbacks.Done()
			a.mu.Lock()
			a.noteLocked("duplicate_completion", id)
			a.mu.Unlock()
		}
	case "cleanup":
		if err := a.releaseAdmission(true, true); err != nil {
			return nil, err
		}
		a.releaseDelivery(false, true)
		a.mu.Lock()
		preps := a.preparations
		a.preparations = nil
		a.mu.Unlock()
		for _, release := range preps {
			release()
		}
		deadline := time.Now().Add(a.pipeline.HTTPTimeout())
		for {
			state := a.pipeline.Snapshot()
			if state.Outstanding == 0 && state.PreparationCount == 0 && state.AdmissionUndecided == 0 {
				break
			}
			if !time.Now().Before(deadline) {
				return nil, errors.New("cleanup failed to settle actual work")
			}
			time.Sleep(time.Millisecond)
		}
	case "drain":
		a.mu.Lock()
		a.noteLocked("drain_started", 0)
		a.mu.Unlock()
		before := a.pipeline.Snapshot()
		ctx, cancel := context.WithTimeout(context.Background(), a.pipeline.limits.DrainTimeout+5*time.Second)
		defer cancel()
		err := a.pipeline.Shutdown(ctx)
		after := a.pipeline.Snapshot()
		a.mu.Lock()
		a.drain = map[string]any{"before": before, "after": after, "error": fmt.Sprint(err)}
		a.mu.Unlock()
	default:
		return nil, errors.New("unknown conformance action")
	}
	return a.Snapshot(), nil
}
func (a *Conformance) Snapshot() map[string]any {
	a.mu.Lock()
	defer a.mu.Unlock()
	state := a.pipeline.Snapshot()
	elapsed := false
	for _, fixture := range a.fixture {
		if !time.Now().Before(fixture.deadline) {
			elapsed = true
		}
	}
	seen, callerReleased := false, false
	for _, event := range a.events {
		if event["name"] == "deadline_observed" {
			seen = true
		}
		if event["name"] == "caller_released" {
			callerReleased = true
		}
	}
	if elapsed && !seen {
		a.noteLocked("deadline_observed", 0)
	}
	events := append([]map[string]any{}, a.events...)
	records := append([]map[string]any{}, a.records...)
	held := 0
	for _, delivery := range a.deliveries {
		if !delivery.setup {
			held++
		}
	}
	admissions, lateAdmissions := 0, 0
	for _, admission := range a.admissions {
		if admission.candidate.Pending() {
			admissions++
		}
		if admission.timedOut {
			lateAdmissions++
		}
	}
	setup := map[string]any{}
	for key, value := range a.setup {
		setup[key] = value
	}
	return map[string]any{"enabled": true, "case_id": a.caseID, "fault": a.fault, "events": events, "state": state, "baseline": a.baseline,
		"held_admission": admissions, "held_late_preparation": lateAdmissions, "held_delivery": held, "records": records, "setup": setup, "mutation": a.mutation, "drain": a.drain, "deadline_elapsed": elapsed, "caller_released": callerReleased}
}
