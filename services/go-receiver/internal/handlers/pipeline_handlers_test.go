package handlers

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/dtkmn/go-adtech-receiver/internal/kafka"
	"github.com/dtkmn/go-adtech-receiver/internal/models"
	"github.com/gin-gonic/gin"
)

type callbackDispatcher struct{ calls chan func(error) }

func (d *callbackDispatcher) Dispatch(_ context.Context, _ kafka.Record, done func(error)) {
	d.calls <- done
}
func (d *callbackDispatcher) Close(context.Context) error { return nil }
func handlerRouter(receiver *Receiver) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(receiver.PreparationMiddleware())
	r.POST("/bid-request", receiver.ReceiveBid)
	return r
}

const requestBody = `{"id":"same-id","site":{},"device":{"lmt":0}}`

func response(router http.Handler, body string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	r := httptest.NewRequest(http.MethodPost, "/bid-request", strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	router.ServeHTTP(w, r)
	return w
}
func nextCallback(t *testing.T, d *callbackDispatcher) func(error) {
	t.Helper()
	select {
	case done := <-d.calls:
		return done
	case <-time.After(time.Second):
		t.Fatal("no dispatch")
		return nil
	}
}

func TestEnqueueReceiptPrecedesSDKOutcome(t *testing.T) {
	d := &callbackDispatcher{calls: make(chan func(error), 1)}
	p := kafka.NewPipeline(kafka.DeliveryModeEnqueue, kafka.DefaultLimits(), d)
	w := response(handlerRouter(NewReceiver(p, "bids")), requestBody)
	if w.Code != 200 {
		t.Fatalf("enqueue=%d", w.Code)
	}
	done := nextCallback(t, d)
	s := p.Snapshot()
	if s.Outstanding != 1 || s.PreparationCount != 0 {
		t.Fatalf("receipt/temporary ownership: %+v", s)
	}
	done(errors.New("immediate SDK failure"))
	s = p.Snapshot()
	if s.ClientFailed != 1 || w.Code != 200 {
		t.Fatalf("postcommit failure revoked admission: %+v", s)
	}
}

func TestConfirmWaitsAndTimeoutDoesNotRelease(t *testing.T) {
	for _, outcome := range []string{"success", "failure", "deadline"} {
		t.Run(outcome, func(t *testing.T) {
			d := &callbackDispatcher{calls: make(chan func(error), 1)}
			l := kafka.DefaultLimits()
			l.HTTPTimeout = time.Second
			if outcome == "deadline" {
				l.HTTPTimeout = 5 * time.Millisecond
			}
			p := kafka.NewPipeline(kafka.DeliveryModeConfirm, l, d)
			r := handlerRouter(NewReceiver(p, "bids"))
			finished := make(chan *httptest.ResponseRecorder, 1)
			go func() { finished <- response(r, requestBody) }()
			done := nextCallback(t, d)
			if p.Snapshot().PreparationCount != 0 {
				t.Fatal("confirm retained temporary preparation while awaiting delivery")
			}
			select {
			case <-finished:
				t.Fatal("response preceded SDK outcome/deadline")
			default:
			}
			want := 503
			switch outcome {
			case "success":
				done(nil)
				want = 200
			case "failure":
				done(errors.New("failed"))
			}
			select {
			case w := <-finished:
				if w.Code != want {
					t.Fatalf("response=%d want%d", w.Code, want)
				}
			case <-time.After(time.Second):
				t.Fatal("HTTP decision did not finish")
			}
			if outcome == "deadline" {
				s := p.Snapshot()
				if s.Outstanding != 1 || s.ClientFailed != 0 || s.HTTPDeadlines != 1 {
					t.Fatalf("timeout freed/falsified SDK work: %+v", s)
				}
				done(nil)
				if p.Snapshot().ClientSucceeded != 1 {
					t.Fatal("late success missing")
				}
			}
		})
	}
}

func TestSerializationRejectsCandidateBeforeReceipt(t *testing.T) {
	d := &callbackDispatcher{calls: make(chan func(error), 1)}
	p := kafka.NewPipeline(kafka.DeliveryModeEnqueue, kafka.DefaultLimits(), d)
	receiver := NewReceiver(p, "bids")
	receiver.Serialize = func(models.BidRequest) ([]byte, error) { return nil, errors.New("injected serialization") }
	w := response(handlerRouter(receiver), requestBody)
	s := p.Snapshot()
	if w.Code != 500 || s.AdmissionCandidates != 1 || s.AdmissionRejected != 1 || s.Admitted != 0 || s.PreparationCount != 0 {
		t.Fatalf("response%d state%+v", w.Code, s)
	}
	select {
	case <-d.calls:
		t.Fatal("serialization rejection dispatched")
	default:
	}
}

func TestHTTPOnlyAndFiltersBypassSerialization(t *testing.T) {
	for _, mode := range []string{kafka.DeliveryModeHttpOnly, kafka.DeliveryModeEnqueue} {
		p := kafka.NewPipeline(mode, kafka.DefaultLimits(), nil)
		receiver := NewReceiver(p, "bids")
		receiver.Serialize = func(models.BidRequest) ([]byte, error) { t.Fatal("unexpected serialization"); return nil, nil }
		body := requestBody
		want := 200
		if mode == kafka.DeliveryModeEnqueue {
			body = strings.Replace(body, `"lmt":0`, `"lmt":1`, 1)
			want = 204
		}
		if w := response(handlerRouter(receiver), body); w.Code != want {
			t.Fatalf("response%d", w.Code)
		}
		if p.Snapshot().AdmissionCandidates != 0 {
			t.Fatal("bypassed request formed Kafka candidate")
		}
	}
}

func TestActualBodyByteBound(t *testing.T) {
	p := kafka.NewPipeline(kafka.DeliveryModeHttpOnly, kafka.DefaultLimits(), nil)
	r := handlerRouter(NewReceiver(p, "bids"))
	for _, n := range []int{65536, 65537} {
		body := requestBody + strings.Repeat(" ", n-len(requestBody))
		w := response(r, body)
		want := 200
		if n > 65536 {
			want = 413
		}
		if w.Code != want {
			t.Fatalf("%d bytes: status%d", n, w.Code)
		}
	}
	if p.Snapshot().PreparationCount != 0 {
		t.Fatal("body-limit path leaked permit")
	}
}

type countedBody struct{ reads int }

func (b *countedBody) Read([]byte) (int, error) { b.reads++; return 0, io.EOF }
func (b *countedBody) Close() error             { return nil }
func TestPreparationRefusalDoesNotReadBody(t *testing.T) {
	l := kafka.DefaultLimits()
	l.MaxPreparation = 1
	p := kafka.NewPipeline(kafka.DeliveryModeHttpOnly, l, nil)
	release, err := p.AcquirePreparation()
	if err != nil {
		t.Fatal(err)
	}
	defer release()
	body := &countedBody{}
	r := httptest.NewRequest(http.MethodPost, "/bid-request", body)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handlerRouter(NewReceiver(p, "bids")).ServeHTTP(w, r)
	if w.Code != 503 || body.reads != 0 || p.Snapshot().PreparationRejected != 1 {
		t.Fatalf("status%d reads%d state%+v", w.Code, body.reads, p.Snapshot())
	}
}

func TestShutdownFencesLiveSerializationWithoutEarlyPermitRelease(t *testing.T) {
	d := &callbackDispatcher{calls: make(chan func(error), 1)}
	p := kafka.NewPipeline(kafka.DeliveryModeEnqueue, kafka.DefaultLimits(), d)
	receiver := NewReceiver(p, "bids")
	started, release := make(chan struct{}), make(chan struct{})
	receiver.Serialize = func(models.BidRequest) ([]byte, error) { close(started); <-release; return []byte(`{}`), nil }
	r := handlerRouter(receiver)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- response(r, requestBody) }()
	<-started
	p.CloseIntake()
	s := p.Snapshot()
	if s.PreparationCount != 1 || s.AdmissionRejected != 1 || s.AdmissionUndecided != 0 {
		t.Fatalf("shutdown prematurely freed live preparation: %+v", s)
	}
	close(release)
	if w := <-finished; w.Code != 503 {
		t.Fatalf("fenced response%d", w.Code)
	}
	if p.Snapshot().PreparationCount != 0 {
		t.Fatal("live preparation did not eventually release")
	}
	select {
	case <-d.calls:
		t.Fatal("fenced record dispatched later")
	default:
	}
}
