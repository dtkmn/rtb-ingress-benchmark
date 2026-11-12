package kafka

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	kgo "github.com/segmentio/kafka-go"
)

type dispatchCall struct {
	record   Record
	complete func(error)
}
type heldDispatcher struct {
	calls   chan dispatchCall
	closeFn func(context.Context) error
}

func (d *heldDispatcher) Dispatch(ctx context.Context, r Record, complete func(error)) {
	select {
	case d.calls <- dispatchCall{r, complete}:
	case <-ctx.Done():
		complete(ctx.Err())
	}
}
func (d *heldDispatcher) Close(ctx context.Context) error {
	if d.closeFn != nil {
		return d.closeFn(ctx)
	}
	return nil
}
func dispatcher() *heldDispatcher { return &heldDispatcher{calls: make(chan dispatchCall, 1024)} }
func testLimits() Limits {
	l := DefaultLimits()
	l.HTTPTimeout = time.Second
	l.DrainTimeout = 10 * time.Millisecond
	return l
}
func nextCall(t *testing.T, d *heldDispatcher) dispatchCall {
	t.Helper()
	select {
	case c := <-d.calls:
		return c
	case <-time.After(time.Second):
		t.Fatal("dispatch did not start")
		return dispatchCall{}
	}
}
func commit(t *testing.T, p *Pipeline, r Record) *Receipt {
	t.Helper()
	v, err := p.BeginCandidate(context.Background()).Commit(r)
	if err != nil {
		t.Fatal(err)
	}
	return v
}
func assertBalanced(t *testing.T, s State) {
	t.Helper()
	if s.AdmissionCandidates != s.Admitted+s.AdmissionRejected+s.AdmissionUndecided || s.Admitted != s.ClientSucceeded+s.ClientFailed+s.AbandonedUnknown+s.Outstanding {
		t.Fatalf("unbalanced: %+v", s)
	}
}

func TestAtomicCountAndByteCapacity(t *testing.T) {
	for _, kind := range []string{"count", "bytes"} {
		t.Run(kind, func(t *testing.T) {
			d := dispatcher()
			l := testLimits()
			l.MaxRecords = 1
			l.MaxBytes = 8
			if kind == "bytes" {
				l.MaxRecords = 10
			}
			p := NewPipeline(DeliveryModeEnqueue, l, d)
			first := commit(t, p, Record{Topic: "bids", Key: []byte("same"), Value: []byte("1234")})
			call := nextCall(t, d)
			if _, err := p.BeginCandidate(context.Background()).Commit(Record{Key: []byte("same"), Value: []byte("1")}); err != ErrCapacity {
				t.Fatalf("expected capacity, got %v", err)
			}
			s := p.Snapshot()
			if s.Outstanding != 1 || s.OutstandingBytes != 8 || s.AdmissionRejected != 1 {
				t.Fatalf("bad capacity state: %+v", s)
			}
			assertBalanced(t, s)
			call.complete(nil)
			second := commit(t, p, Record{Key: []byte("same"), Value: []byte("1")})
			if first.AttemptID == second.AttemptID {
				t.Fatal("duplicate request keys shared attempt identity")
			}
			nextCall(t, d).complete(nil)
			assertBalanced(t, p.Snapshot())
		})
	}
}

func TestImmutableReceiptAndOneSettlement(t *testing.T) {
	d := dispatcher()
	p := NewPipeline(DeliveryModeEnqueue, testLimits(), d)
	key, value := []byte("original"), []byte("payload")
	commit(t, p, Record{Topic: "topic", Key: key, Value: value})
	key[0] = 'X'
	value[0] = 'Y'
	call := nextCall(t, d)
	if string(call.record.Key) != "original" || string(call.record.Value) != "payload" || call.record.Topic != "topic" {
		t.Fatal("caller mutation reached owned record")
	}
	call.complete(nil)
	call.complete(errors.New("duplicate"))
	s := p.Snapshot()
	if s.ClientSucceeded != 1 || s.ClientFailed != 0 || s.OutstandingBytes != 0 {
		t.Fatalf("duplicate settlement: %+v", s)
	}
	assertBalanced(t, s)
}

func TestConcurrentFinalSlot(t *testing.T) {
	d := dispatcher()
	l := testLimits()
	l.MaxRecords = 1
	p := NewPipeline(DeliveryModeEnqueue, l, d)
	barrier := make(chan struct{})
	var wg sync.WaitGroup
	var mu sync.Mutex
	admitted := 0
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			candidate := p.BeginCandidate(context.Background())
			<-barrier
			_, err := candidate.Commit(Record{Key: []byte("duplicate-id"), Value: []byte("v")})
			if err == nil {
				mu.Lock()
				admitted++
				mu.Unlock()
			} else if err != ErrCapacity {
				t.Errorf("unexpected rejection: %v", err)
			}
		}()
	}
	close(barrier)
	wg.Wait()
	if admitted != 1 {
		t.Fatalf("%d winners", admitted)
	}
	s := p.Snapshot()
	if s.AdmissionRejected != 31 || s.Outstanding != 1 {
		t.Fatalf("race state: %+v", s)
	}
	assertBalanced(t, s)
	nextCall(t, d).complete(nil)
}

func TestHTTPDeadlineRetainsCapacityUntilLateOutcome(t *testing.T) {
	d := dispatcher()
	p := NewPipeline(DeliveryModeConfirm, testLimits(), d)
	r := commit(t, p, Record{Value: []byte("held")})
	call := nextCall(t, d)
	if err := r.Wait(context.Background(), time.Now().Add(time.Millisecond)); err != ErrDeadline {
		t.Fatalf("expected timeout: %v", err)
	}
	s := p.Snapshot()
	if s.Outstanding != 1 || s.OutstandingBytes != 4 || s.ClientFailed != 0 {
		t.Fatalf("premature release/failure: %+v", s)
	}
	call.complete(nil)
	s = p.Snapshot()
	if s.ClientSucceeded != 1 || s.Outstanding != 0 {
		t.Fatalf("late outcome lost: %+v", s)
	}
	assertBalanced(t, s)
}

func TestDisconnectDoesNotCancelAdmittedSDKWork(t *testing.T) {
	d := dispatcher()
	p := NewPipeline(DeliveryModeConfirm, testLimits(), d)
	r := commit(t, p, Record{Value: []byte("held")})
	call := nextCall(t, d)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := r.Wait(ctx, time.Now().Add(time.Second)); err != context.Canceled {
		t.Fatalf("disconnect: %v", err)
	}
	s := p.Snapshot()
	if s.Outstanding != 1 || s.ClientFailed != 0 || s.HTTPDisconnects != 1 || s.HTTPDeadlines != 0 {
		t.Fatalf("disconnect changed SDK accounting: %+v", s)
	}
	call.complete(nil)
	if p.Snapshot().ClientSucceeded != 1 {
		t.Fatal("late success lost")
	}
}

func TestExpiredCandidateCannotCommitOrDispatch(t *testing.T) {
	d := dispatcher()
	p := NewPipeline(DeliveryModeEnqueue, testLimits(), d)
	c := p.BeginCandidate(context.Background())
	c.deadline = time.Now().Add(-time.Nanosecond)
	if _, err := c.Commit(Record{Value: []byte("fenced")}); err != ErrDeadline {
		t.Fatal(err)
	}
	s := p.Snapshot()
	if s.AdmissionRejected != 1 || s.Admitted != 0 || s.OutstandingBytes != 0 || s.HTTPDeadlines != 1 {
		t.Fatalf("expired candidate: %+v", s)
	}
	assertBalanced(t, s)
	select {
	case <-d.calls:
		t.Fatal("late commit dispatched")
	default:
	}
}

func TestCandidateFencesAndRejectionAccounting(t *testing.T) {
	d := dispatcher()
	p := NewPipeline(DeliveryModeEnqueue, testLimits(), d)
	ctx, cancel := context.WithCancel(context.Background())
	c := p.BeginCandidate(ctx)
	cancel()
	if _, err := c.Commit(Record{}); err != ErrDeadline {
		t.Fatal(err)
	}
	c = p.BeginCandidate(context.Background())
	c.Reject("serialization")
	c.Reject("duplicate")
	c = p.BeginCandidate(context.Background())
	p.CloseIntake()
	if _, err := c.Commit(Record{}); err != ErrClosed {
		t.Fatal(err)
	}
	s := p.Snapshot()
	if s.AdmissionCandidates != 3 || s.AdmissionRejected != 3 || s.Admitted != 0 {
		t.Fatalf("bad rejection counters: %+v", s)
	}
	assertBalanced(t, s)
	select {
	case <-d.calls:
		t.Fatal("fenced record dispatched")
	default:
	}
}

func TestPreparationBoundsAndDelayedRelease(t *testing.T) {
	l := testLimits()
	l.MaxPreparation = 1
	p := NewPipeline(DeliveryModeHttpOnly, l, nil)
	release, err := p.AcquirePreparation()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.AcquirePreparation(); err != ErrCapacity {
		t.Fatal(err)
	}
	p.CloseIntake()
	if p.Snapshot().PreparationCount != 1 {
		t.Fatal("close released live preparation")
	}
	release()
	release()
	s := p.Snapshot()
	if s.PreparationCount != 0 || s.PreparationRejected != 1 || s.AdmissionCandidates != 0 {
		t.Fatalf("bad preparation: %+v", s)
	}
}

func TestDrainWaitsThenSettles(t *testing.T) {
	d := dispatcher()
	l := testLimits()
	l.DrainTimeout = time.Second
	p := NewPipeline(DeliveryModeEnqueue, l, d)
	commit(t, p, Record{Value: []byte("v")})
	call := nextCall(t, d)
	done := make(chan error, 1)
	go func() { done <- p.Shutdown(context.Background()) }()
	for !p.Snapshot().IntakeClosed {
		time.Sleep(time.Millisecond)
	}
	if p.Snapshot().Outstanding != 1 {
		t.Fatal("shutdown released before outcome")
	}
	call.complete(nil)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	s := p.Snapshot()
	if !s.TeardownConfirmed || s.DrainResult != "settled" || s.ClientSucceeded != 1 {
		t.Fatalf("bad drain: %+v", s)
	}
}

func TestDrainAbandonsOnlyAfterConfirmedTeardown(t *testing.T) {
	d := dispatcher()
	closing, release := make(chan struct{}), make(chan struct{})
	d.closeFn = func(context.Context) error { close(closing); <-release; return nil }
	p := NewPipeline(DeliveryModeEnqueue, testLimits(), d)
	commit(t, p, Record{Value: []byte("v")})
	call := nextCall(t, d)
	done := make(chan error, 1)
	go func() { done <- p.Shutdown(context.Background()) }()
	<-closing
	s := p.Snapshot()
	if s.Outstanding != 1 || s.AbandonedUnknown != 0 || s.TeardownConfirmed {
		t.Fatalf("unconfirmed teardown freed work: %+v", s)
	}
	close(release)
	if err := <-done; err == nil {
		t.Fatal("incomplete drain reported success")
	}
	call.complete(nil)
	s = p.Snapshot()
	if s.AbandonedUnknown != 1 || s.Outstanding != 0 || s.ClientSucceeded != 0 || !s.TeardownConfirmed {
		t.Fatalf("bad abandonment: %+v", s)
	}
	assertBalanced(t, s)
}

func TestUnconfirmedTeardownKeepsCredits(t *testing.T) {
	d := dispatcher()
	d.closeFn = func(context.Context) error { return errors.New("teardown failed") }
	p := NewPipeline(DeliveryModeEnqueue, testLimits(), d)
	commit(t, p, Record{Value: []byte("v")})
	call := nextCall(t, d)
	if err := p.Shutdown(context.Background()); err == nil {
		t.Fatal("failed teardown accepted")
	}
	s := p.Snapshot()
	if s.Outstanding != 1 || s.AbandonedUnknown != 0 || s.TeardownConfirmed {
		t.Fatalf("credits lost: %+v", s)
	}
	call.complete(errors.New("late outcome"))
	assertBalanced(t, p.Snapshot())
}

type asyncTestWriter struct{ message kgo.Message }

func (w *asyncTestWriter) WriteMessages(_ context.Context, m ...kgo.Message) error {
	w.message = m[0]
	return nil
}
func (w *asyncTestWriter) Close() error { return nil }
func TestAsyncWriterReturnIsNotDelivery(t *testing.T) {
	w := &asyncTestWriter{}
	d := &kafkaDispatcher{writer: w}
	completed := false
	d.Dispatch(context.Background(), Record{Key: []byte("k"), Value: []byte("v")}, func(error) { completed = true })
	if completed {
		t.Fatal("async WriteMessages nil was called delivery")
	}
	completeWriterMessages([]kgo.Message{w.message}, nil)
	if !completed {
		t.Fatal("actual Completion did not settle")
	}
}

func TestUnsupportedConfirmAcks(t *testing.T) {
	t.Setenv("BENCHMARK_DELIVERY_MODE", "confirm")
	t.Setenv("BENCHMARK_KAFKA_ACKS", "0")
	if err := InitKafkaProducer("uncontacted:9092"); err == nil {
		t.Fatal("unsupported confirm profile initialized")
	}
}

func TestShutdownWaitDeadlinePublishesFailedSnapshotBeforeReturn(t *testing.T) {
	d := dispatcher()
	closeStarted := make(chan struct{})
	closeRelease := make(chan struct{})
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { close(closeRelease) }) }
	d.closeFn = func(context.Context) error {
		close(closeStarted)
		<-closeRelease
		return nil // teardown is confirmed only after the test releases it
	}
	p := NewPipeline(DeliveryModeEnqueue, testLimits(), d)
	if _, err := p.BeginCandidate(context.Background()).Commit(Record{Topic: "bids", Key: []byte("id"), Value: []byte("value")}); err != nil {
		t.Fatal(err)
	}
	_ = nextCall(t, d)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	defer release()
	returned := make(chan error, 1)
	go func() { returned <- p.Shutdown(ctx) }()
	select {
	case <-closeStarted:
	case <-time.After(time.Second):
		t.Fatal("producer teardown did not start")
	}
	cancel() // The worker remains blocked in Close while the waiter expires.
	select {
	case err := <-returned:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("wait result = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("shutdown waiter did not return")
	}
	s := p.Snapshot()
	if s.DrainResult != "teardown_unconfirmed" || !s.IntakeClosed || s.TeardownConfirmed || s.Outstanding != 1 || s.OutstandingBytes != 7 || s.AbandonedUnknown != 0 {
		t.Fatalf("timeout must publish failed state with credits held: %+v", s)
	}
	release()
	select {
	case <-p.shutdownDone:
	case <-time.After(time.Second):
		t.Fatal("late teardown did not finish")
	}
	if p.Snapshot().DrainResult == "settled" {
		t.Fatal("late teardown promoted the failed wait to success")
	}
	assertBalanced(t, p.Snapshot())
}

func TestCompletedShutdownPrecedesCancelledWaiter(t *testing.T) {
	p := NewPipeline(DeliveryModeHttpOnly, testLimits(), nil)
	ctx, cancel := context.WithCancel(context.Background())
	if err := p.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	cancel()
	if err := p.Shutdown(ctx); err != nil {
		t.Fatalf("completed clean shutdown returned waiter error: %v", err)
	}
	if p.Snapshot().DrainResult != "settled" {
		t.Fatalf("completed shutdown state: %+v", p.Snapshot())
	}
}
