package kafka

import (
	"context"
	"errors"
	"sync"
	"time"
)

var (
	ErrUnavailable = errors.New("pipeline unavailable")
	ErrClosed      = errors.New("pipeline intake closed")
	ErrCapacity    = errors.New("pipeline capacity exhausted")
	ErrDeadline    = errors.New("HTTP outcome deadline")
)

const MaxBodyBytes = 65536

type Limits struct {
	MaxRecords     int           `json:"max_records"`
	MaxBytes       int64         `json:"max_bytes"`
	MaxPreparation int           `json:"max_preparation_requests"`
	HTTPTimeout    time.Duration `json:"-"`
	DrainTimeout   time.Duration `json:"-"`
}

func DefaultLimits() Limits {
	return Limits{1024, 16 << 20, 128, 30 * time.Second, 30 * time.Second}
}

func LoadLimits() Limits {
	return Limits{
		MaxRecords:     parsePositiveInt("BENCHMARK_PIPELINE_MAX_RECORDS", 1024),
		MaxBytes:       int64(parsePositiveInt("BENCHMARK_PIPELINE_MAX_BYTES", 16<<20)),
		MaxPreparation: parsePositiveInt("BENCHMARK_PREPARATION_MAX_REQUESTS", 128),
		HTTPTimeout:    time.Duration(parsePositiveInt("BENCHMARK_HTTP_OUTCOME_TIMEOUT_MS", 30000)) * time.Millisecond,
		DrainTimeout:   time.Duration(parsePositiveInt("BENCHMARK_DRAIN_TIMEOUT_MS", 30000)) * time.Millisecond,
	}
}

// Record buffers are copied at commitment. Dispatcher receives the owned copy,
// never the caller's byte slices. One Dispatch is one logical application send.
type Record struct {
	Topic      string
	Key, Value []byte
	attemptID  uint64 // Internal dispatch identity, distinct from request key.
}
type Dispatcher interface {
	Dispatch(context.Context, Record, func(error))
	// A nil result guarantees that no dispatch or completion can remain live.
	Close(context.Context) error
}

type State struct {
	AdmissionCandidates uint64            `json:"admission_candidates"`
	AdmissionRejected   uint64            `json:"admission_rejected"`
	AdmissionUndecided  uint64            `json:"admission_undecided"`
	Admitted            uint64            `json:"admitted"`
	ClientSucceeded     uint64            `json:"client_succeeded"`
	ClientFailed        uint64            `json:"client_failed"`
	AbandonedUnknown    uint64            `json:"abandoned_unknown"`
	Outstanding         uint64            `json:"outstanding"`
	OutstandingBytes    int64             `json:"outstanding_bytes"`
	PreparationCount    int               `json:"preparation_count"`
	PreparationRejected uint64            `json:"preparation_rejected"`
	HTTPDeadlines       uint64            `json:"http_deadlines"`
	HTTPDisconnects     uint64            `json:"http_disconnects"`
	HTTPStatuses        map[int]uint64    `json:"http_statuses"`
	RejectionReasons    map[string]uint64 `json:"admission_rejection_reasons"`
	IntakeClosed        bool              `json:"intake_closed"`
	TeardownConfirmed   bool              `json:"teardown_confirmed"`
	DrainResult         string            `json:"drain_result"`
	DeliveryMode        string            `json:"delivery_mode"`
	Limits              Limits            `json:"bounds"`
	HTTPTimeoutMS       int64             `json:"http_outcome_timeout_ms"`
	DrainTimeoutMS      int64             `json:"drain_timeout_ms"`
	SDKBatchMaxRecords  int               `json:"sdk_batch_max_records,omitempty"`
}

type Pipeline struct {
	mu                 sync.Mutex
	limits             Limits
	mode               string
	dispatcher         Dispatcher
	state              State
	seq                uint64
	candidates         map[uint64]*Candidate
	receipts           map[uint64]*Receipt
	changed            chan struct{}
	dispatchCtx        context.Context
	cancelDispatch     context.CancelFunc
	dispatches         sync.WaitGroup
	shutdown           sync.Once
	shutdownDone       chan struct{}
	shutdownErr        error
	shutdownWaitFailed bool // guarded by mu; a returned wait timeout stays failed
	intakeClosedAt     time.Time
	conformance        *Conformance // Nil normally; no fault ledger allocated.
}

type Candidate struct {
	p        *Pipeline
	id       uint64
	deadline time.Time
	ctx      context.Context
	finished bool // guarded by Pipeline.mu
}

type Receipt struct {
	p         *Pipeline
	AttemptID uint64
	done      chan struct{}
	err       error // written before done closes, guarded by Pipeline.mu
	bytes     int64
}

func NewPipeline(mode string, limits Limits, dispatcher Dispatcher) *Pipeline {
	if limits.MaxRecords <= 0 || limits.MaxBytes <= 0 || limits.MaxPreparation <= 0 || limits.HTTPTimeout <= 0 || limits.DrainTimeout <= 0 {
		panic("positive pipeline limits required")
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Pipeline{limits: limits, mode: mode, dispatcher: dispatcher, candidates: make(map[uint64]*Candidate), receipts: make(map[uint64]*Receipt), changed: make(chan struct{}), dispatchCtx: ctx, cancelDispatch: cancel, shutdownDone: make(chan struct{}), state: State{HTTPStatuses: make(map[int]uint64), RejectionReasons: make(map[string]uint64), DrainResult: "not_started"}}
}

func (p *Pipeline) Mode() string               { return p.mode }
func (p *Pipeline) HTTPTimeout() time.Duration { return p.limits.HTTPTimeout }
func (p *Pipeline) Conformance() *Conformance  { return p.conformance }
func (p *Pipeline) notifyLocked()              { close(p.changed); p.changed = make(chan struct{}) }

func (p *Pipeline) AcquirePreparation() (func(), error) {
	p.mu.Lock()
	if p.state.IntakeClosed || p.state.PreparationCount >= p.limits.MaxPreparation {
		p.state.PreparationRejected++
		p.mu.Unlock()
		return nil, ErrCapacity
	}
	p.state.PreparationCount++
	p.mu.Unlock()
	var once sync.Once
	return func() { once.Do(func() { p.mu.Lock(); p.state.PreparationCount--; p.notifyLocked(); p.mu.Unlock() }) }, nil
}

func (p *Pipeline) BeginCandidate(ctx context.Context) *Candidate {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.seq++
	c := &Candidate{p: p, id: p.seq, deadline: time.Now().Add(p.limits.HTTPTimeout), ctx: ctx}
	p.candidates[c.id] = c
	p.state.AdmissionCandidates++
	p.state.AdmissionUndecided++
	if p.state.IntakeClosed {
		c.rejectLocked("intake_closed")
	}
	return c
}

func (c *Candidate) rejectLocked(reason string) {
	if c.finished {
		return
	}
	c.finished = true
	delete(c.p.candidates, c.id)
	c.p.state.AdmissionUndecided--
	c.p.state.AdmissionRejected++
	c.p.state.RejectionReasons[reason]++
	c.p.notifyLocked()
}

func (c *Candidate) Reject(reason string) {
	c.p.mu.Lock()
	defer c.p.mu.Unlock()
	c.rejectLocked(reason)
}

func (c *Candidate) Commit(record Record) (*Receipt, error) {
	p := c.p
	p.mu.Lock()
	if c.finished || p.state.IntakeClosed {
		c.rejectLocked("intake_closed")
		p.mu.Unlock()
		return nil, ErrClosed
	}
	if !time.Now().Before(c.deadline) || c.ctx.Err() != nil {
		c.rejectLocked("deadline_or_disconnect")
		p.observeAbortLocked(c.ctx, c.deadline)
		p.mu.Unlock()
		return nil, ErrDeadline
	}
	if p.dispatcher == nil {
		c.rejectLocked("unavailable")
		p.mu.Unlock()
		return nil, ErrUnavailable
	}
	size := int64(len(record.Key)) + int64(len(record.Value))
	if len(p.receipts) >= p.limits.MaxRecords || size > p.limits.MaxBytes-p.state.OutstandingBytes {
		c.rejectLocked("capacity")
		p.mu.Unlock()
		return nil, ErrCapacity
	}
	owned := Record{Topic: record.Topic, Key: append([]byte(nil), record.Key...), Value: append([]byte(nil), record.Value...), attemptID: c.id}
	// Copying is bounded, but can cross the deadline: fence before commitment.
	if !time.Now().Before(c.deadline) || c.ctx.Err() != nil {
		c.rejectLocked("deadline_or_disconnect")
		p.observeAbortLocked(c.ctx, c.deadline)
		p.mu.Unlock()
		return nil, ErrDeadline
	}
	c.finished = true
	delete(p.candidates, c.id)
	p.state.AdmissionUndecided--
	r := &Receipt{p: p, AttemptID: c.id, done: make(chan struct{}), bytes: size}
	p.receipts[r.AttemptID] = r
	p.state.Admitted++
	p.state.Outstanding++
	p.state.OutstandingBytes += size
	p.dispatches.Add(1) // Add before closing intake can start Wait.
	p.notifyLocked()
	p.mu.Unlock()
	if p.conformance != nil {
		p.conformance.committed(r, c.deadline, record)
	}
	go func() {
		defer p.dispatches.Done()
		defer func() {
			if recover() != nil {
				r.complete(errors.New("dispatcher panic"))
			}
		}()
		p.dispatcher.Dispatch(p.dispatchCtx, owned, r.complete)
	}()
	return r, nil
}

func (r *Receipt) complete(err error) {
	p := r.p
	p.mu.Lock()
	if _, live := p.receipts[r.AttemptID]; !live {
		p.mu.Unlock()
		return
	}
	delete(p.receipts, r.AttemptID)
	p.state.Outstanding--
	p.state.OutstandingBytes -= r.bytes
	if err == nil {
		p.state.ClientSucceeded++
	} else {
		p.state.ClientFailed++
	}
	r.err = err
	close(r.done)
	p.notifyLocked()
	p.mu.Unlock()
	if p.conformance != nil {
		p.conformance.outcome(r.AttemptID, err)
	}
}

func (r *Receipt) Wait(ctx context.Context, deadline time.Time) error {
	timer := time.NewTimer(time.Until(deadline))
	defer timer.Stop()
	select {
	case <-r.done:
		if !time.Now().Before(deadline) {
			r.p.observeDeadline()
			return ErrDeadline
		}
		return r.err
	case <-ctx.Done():
		r.p.mu.Lock()
		r.p.observeAbortLocked(ctx, deadline)
		r.p.mu.Unlock()
		return ctx.Err()
	case <-timer.C:
		r.p.observeDeadline()
		return ErrDeadline
	}
}
func (c *Candidate) Deadline() time.Time { return c.deadline }
func (c *Candidate) Pending() bool       { c.p.mu.Lock(); defer c.p.mu.Unlock(); return !c.finished }
func (p *Pipeline) observeDeadline()     { p.mu.Lock(); p.state.HTTPDeadlines++; p.mu.Unlock() }
func (p *Pipeline) observeAbortLocked(ctx context.Context, deadline time.Time) {
	if !time.Now().Before(deadline) || errors.Is(ctx.Err(), context.DeadlineExceeded) {
		p.state.HTTPDeadlines++
	} else {
		p.state.HTTPDisconnects++
	}
}
func (p *Pipeline) ObserveHTTP(status int) {
	p.mu.Lock()
	p.state.HTTPStatuses[status]++
	p.mu.Unlock()
}

func (p *Pipeline) Snapshot() State {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.state
	s.Limits = p.limits
	s.DeliveryMode = p.mode
	s.HTTPTimeoutMS = p.limits.HTTPTimeout.Milliseconds()
	s.DrainTimeoutMS = p.limits.DrainTimeout.Milliseconds()
	s.HTTPStatuses = make(map[int]uint64, len(p.state.HTTPStatuses))
	for k, v := range p.state.HTTPStatuses {
		s.HTTPStatuses[k] = v
	}
	s.RejectionReasons = make(map[string]uint64, len(p.state.RejectionReasons))
	for k, v := range p.state.RejectionReasons {
		s.RejectionReasons[k] = v
	}
	return s
}

func (p *Pipeline) CloseIntake() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.state.IntakeClosed {
		p.intakeClosedAt = time.Now()
		p.state.IntakeClosed = true
	}
	for _, c := range p.candidates {
		c.rejectLocked("shutdown")
	}
	p.notifyLocked()
}

// Shutdown preserves live credits if teardown cannot be confirmed. It never
// treats the drain deadline alone as permission to abandon an SDK operation.
func (p *Pipeline) Shutdown(ctx context.Context) error {
	p.shutdown.Do(func() {
		p.CloseIntake()
		go func() { p.shutdownErr = p.shutdownPipeline(ctx); close(p.shutdownDone) }()
	})
	// A completed shutdown takes precedence over an already-cancelled waiter.
	select {
	case <-p.shutdownDone:
		return p.shutdownErr
	default:
	}
	select {
	case <-p.shutdownDone:
		return p.shutdownErr
	case <-ctx.Done():
		p.mu.Lock()
		// The ledger may already be final while the completion channel has not
		// yet closed. A confirmed settled teardown is a completed success.
		if p.state.TeardownConfirmed && p.state.DrainResult == "settled" {
			p.mu.Unlock()
			return nil
		}
		if !p.state.TeardownConfirmed {
			p.shutdownWaitFailed = true
			p.state.DrainResult = "teardown_unconfirmed"
			p.notifyLocked()
		}
		p.mu.Unlock()
		return ctx.Err()
	}
}

func (p *Pipeline) shutdownPipeline(ctx context.Context) error {
	p.CloseIntake()
	p.mu.Lock()
	started := p.intakeClosedAt
	if !p.shutdownWaitFailed {
		p.state.DrainResult = "draining"
	}
	p.mu.Unlock()
	timer := time.NewTimer(time.Until(started.Add(p.limits.DrainTimeout)))
	defer timer.Stop()
	drained := false
	for {
		p.mu.Lock()
		ready := p.state.PreparationCount == 0 && p.state.AdmissionUndecided == 0 && p.state.Outstanding == 0
		changed := p.changed
		p.mu.Unlock()
		if ready {
			drained = true
			break
		}
		select {
		case <-changed:
			continue
		case <-timer.C:
		case <-ctx.Done():
		}
		break
	}
	p.cancelDispatch()
	var teardownErr error
	if p.dispatcher != nil {
		teardownErr = p.dispatcher.Close(ctx)
	}
	if teardownErr == nil {
		done := make(chan struct{})
		go func() { p.dispatches.Wait(); close(done) }()
		select {
		case <-done:
		case <-ctx.Done():
			teardownErr = ctx.Err()
		}
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if teardownErr != nil {
		p.state.DrainResult = "teardown_unconfirmed"
		return teardownErr
	}
	p.state.TeardownConfirmed = true
	for id, r := range p.receipts {
		delete(p.receipts, id)
		p.state.Outstanding--
		p.state.OutstandingBytes -= r.bytes
		p.state.AbandonedUnknown++
		r.err = ErrClosed
		close(r.done)
	}
	p.state.DrainResult = "incomplete"
	if drained && !p.shutdownWaitFailed && p.state.PreparationCount == 0 && p.state.AdmissionUndecided == 0 && p.state.AbandonedUnknown == 0 {
		p.state.DrainResult = "settled"
	}
	p.notifyLocked()
	if p.state.DrainResult != "settled" {
		return errors.New("pipeline drain incomplete")
	}
	return nil
}
