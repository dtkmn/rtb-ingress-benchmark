package handlers

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/dtkmn/go-adtech-receiver/internal/kafka"
	"github.com/dtkmn/go-adtech-receiver/internal/models"
)

type corpusFixture struct {
	ID       string          `json:"id"`
	Scenario json.RawMessage `json:"scenario"`
	Request  struct {
		Method  string                          `json:"method"`
		Path    string                          `json:"path"`
		Headers []struct{ Name, Value string }  `json:"headers"`
		Body    struct{ Encoding, Data string } `json:"body"`
	} `json:"request"`
	Expectations map[string]struct {
		Status     int               `json:"status"`
		Accounting map[string]uint64 `json:"accounting"`
		Record     *struct {
			Key, Topic string
			Value      json.RawMessage
		} `json:"record"`
	} `json:"expectations"`
}

func normalCorpus(t *testing.T) []corpusFixture {
	t.Helper()
	body, err := os.ReadFile("../../../../tests/conformance/corpus.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []corpusFixture `json:"cases"`
	}
	if err = json.Unmarshal(body, &corpus); err != nil {
		t.Fatal(err)
	}
	var fixtures []corpusFixture
	for _, fixture := range corpus.Cases {
		if len(fixture.Scenario) == 0 {
			fixtures = append(fixtures, fixture)
		}
	}
	if len(fixtures) != 52 {
		t.Fatalf("normal corpus changed: got%d want52; review the shared gate coverage", len(fixtures))
	}
	return fixtures
}

type intentDispatcher struct {
	record  chan kafka.Record
	settled chan struct{}
}

func (d *intentDispatcher) Dispatch(_ context.Context, record kafka.Record, complete func(error)) {
	d.record <- record
	complete(nil)
	close(d.settled)
}
func (d *intentDispatcher) Close(context.Context) error { return nil }

func TestSharedNormalConformanceCorpus(t *testing.T) {
	for _, fixture := range normalCorpus(t) {
		for _, mode := range []string{kafka.DeliveryModeHttpOnly, kafka.DeliveryModeConfirm, kafka.DeliveryModeEnqueue} {
			t.Run(fixture.ID+"/"+mode, func(t *testing.T) {
				expect := fixture.Expectations[mode]
				body := []byte(fixture.Request.Body.Data)
				if fixture.Request.Body.Encoding == "base64" {
					var err error
					body, err = base64.StdEncoding.DecodeString(fixture.Request.Body.Data)
					if err != nil {
						t.Fatal(err)
					}
				}
				d := &intentDispatcher{make(chan kafka.Record, 1), make(chan struct{})}
				var dispatcher kafka.Dispatcher = d
				if mode == kafka.DeliveryModeHttpOnly {
					dispatcher = nil
				}
				p := kafka.NewPipeline(mode, kafka.DefaultLimits(), dispatcher)
				receiver := NewReceiver(p, "configured-topic")
				serialize := receiver.Serialize
				serializations := 0
				receiver.Serialize = func(request models.BidRequest) ([]byte, error) { serializations++; return serialize(request) }
				req := httptest.NewRequest(fixture.Request.Method, fixture.Request.Path, bytes.NewReader(body))
				for _, header := range fixture.Request.Headers {
					req.Header.Add(header.Name, header.Value)
				}
				w := httptest.NewRecorder()
				handlerRouter(receiver).ServeHTTP(w, req)
				if w.Code != expect.Status {
					t.Fatalf("status%d want%d: %s", w.Code, expect.Status, w.Body.String())
				}
				if w.Code == 204 && w.Body.Len() != 0 {
					t.Fatal("filter response body is not empty")
				}
				if w.Code == 200 {
					semanticJSON(t, w.Body.Bytes(), []byte(`{"status":"accepted"}`))
				}
				if expect.Record != nil {
					select {
					case record := <-d.record:
						if record.Topic != expect.Record.Topic || string(record.Key) != expect.Record.Key {
							t.Fatalf("topic/key changed: %+v", record)
						}
						semanticJSON(t, record.Value, expect.Record.Value)
						if !json.Valid(record.Value) || !utf8.Valid(record.Value) {
							t.Fatal("invalid UTF-8 JSON record")
						}
						t.Logf("owned intent: key_bytes=%d value_bytes=%d; compact encoding/json with standard HTML/U+2028/U+2029 escaping", len(record.Key), len(record.Value))
					case <-time.After(time.Second):
						t.Fatal("expected record never dispatched")
					}
					select {
					case <-d.settled:
					case <-time.After(time.Second):
						t.Fatal("client completion did not settle")
					}
					if serializations != 1 {
						t.Fatalf("serializations%d", serializations)
					}
				} else if serializations != 0 || len(d.record) != 0 {
					t.Fatalf("bypass serialized/dispatched: calls%d records%d", serializations, len(d.record))
				}
				s := p.Snapshot()
				actual := map[string]uint64{"admission_candidates": s.AdmissionCandidates, "admitted": s.Admitted, "admission_rejected": s.AdmissionRejected, "admission_undecided": s.AdmissionUndecided, "client_succeeded": s.ClientSucceeded, "client_failed": s.ClientFailed, "abandoned_unknown": s.AbandonedUnknown, "outstanding": s.Outstanding, "preparation_rejected": s.PreparationRejected}
				for key, want := range expect.Accounting {
					if actual[key] != want {
						t.Fatalf("%s=%d want%d", key, actual[key], want)
					}
				}
				if s.PreparationCount != 0 || s.OutstandingBytes != 0 {
					t.Fatalf("temporary/pipeline credits leaked: %+v", s)
				}
			})
		}
	}
}

func semanticJSON(t *testing.T, actual, expected []byte) {
	t.Helper()
	var got, want any
	if err := json.Unmarshal(actual, &got); err != nil {
		t.Fatalf("actual JSON: %v", err)
	}
	if err := json.Unmarshal(expected, &want); err != nil {
		t.Fatalf("expected JSON: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("canonical JSON mismatch\ngot%s\nwant%s", actual, expected)
	}
}

func TestMediaRejectionPrecedesBodyReadAndPreparation(t *testing.T) {
	for _, header := range []http.Header{
		{}, {"Content-Type": {"application/json", "application/json"}},
		{"Content-Type": {"application/json"}, "content-type": {"application/json"}},
		{"Content-Type": {"application/json; charset=utf-8; charset=utf-8"}},
		{"Content-Type": {"application/json; other=1"}},
		{"Content-Type": {"application/jſon"}},
		{"Content-Type": {"application/json"}, "Content-Encoding": {"identity", "identity"}},
		{"Content-Type": {"application/json"}, "Content-Encoding": {""}},
	} {
		p := kafka.NewPipeline(kafka.DeliveryModeHttpOnly, kafka.DefaultLimits(), nil)
		body := &countedBody{}
		req := httptest.NewRequest(http.MethodPost, "/bid-request", body)
		req.Header = header
		req.ContentLength = 65537
		w := httptest.NewRecorder()
		handlerRouter(NewReceiver(p, "unused")).ServeHTTP(w, req)
		if w.Code != 415 || body.reads != 0 || p.Snapshot().PreparationRejected != 0 || p.Snapshot().PreparationCount != 0 {
			t.Fatalf("header%v status%d reads%d state%+v", header, w.Code, body.reads, p.Snapshot())
		}
	}
}

func TestBodyLimitWithUnknownLengthAndMixedFaultPrecedence(t *testing.T) {
	for _, row := range []struct {
		length int
		media  string
		want   int
	}{{65536, "application/json", 200}, {65537, "application/json", 413}, {65537, "text/plain", 415}} {
		p := kafka.NewPipeline(kafka.DeliveryModeHttpOnly, kafka.DefaultLimits(), nil)
		body := requestBody + strings.Repeat(" ", row.length-len(requestBody))
		if row.want != 200 {
			body = "!" + body[1:]
		}
		req := httptest.NewRequest(http.MethodPost, "/bid-request", strings.NewReader(body))
		req.Header.Set("Content-Type", row.media)
		req.ContentLength = -1
		req.TransferEncoding = []string{"chunked"}
		w := httptest.NewRecorder()
		handlerRouter(NewReceiver(p, "unused")).ServeHTTP(w, req)
		if w.Code != row.want || p.Snapshot().PreparationCount != 0 {
			t.Fatalf("length%d status%d want%d state%+v", row.length, w.Code, row.want, p.Snapshot())
		}
	}
}

func TestWireDuplicateHeadersAndChunkedBodyBounds(t *testing.T) {
	p := kafka.NewPipeline(kafka.DeliveryModeHttpOnly, kafka.DefaultLimits(), nil)
	server := httptest.NewServer(handlerRouter(NewReceiver(p, "unused")))
	defer server.Close()
	u, _ := url.Parse(server.URL)
	for _, row := range []struct {
		name, headers string
		body          []byte
		chunked       bool
		status        int
	}{
		{"duplicate_content_type", "Content-Type: application/json\r\ncontent-type: application/json\r\n", []byte(requestBody), false, 415},
		{"duplicate_encoding", "Content-Type: application/json\r\nContent-Encoding: identity\r\nContent-Encoding: identity\r\n", []byte(requestBody), false, 415},
		{"chunked_exact", "Content-Type: application/json\r\n", []byte(requestBody + strings.Repeat(" ", 65536-len(requestBody))), true, 200},
		{"chunked_oversize", "Content-Type: application/json\r\n", []byte(requestBody + strings.Repeat(" ", 65537-len(requestBody))), true, 413},
	} {
		t.Run(row.name, func(t *testing.T) {
			connection, err := net.DialTimeout("tcp", u.Host, time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer connection.Close()
			if err = connection.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
				t.Fatal(err)
			}
			var wire bytes.Buffer
			fmt.Fprintf(&wire, "POST /bid-request HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n%s", u.Host, row.headers)
			if row.chunked {
				fmt.Fprintf(&wire, "Transfer-Encoding: chunked\r\n\r\n%x\r\n", len(row.body))
				wire.Write(row.body)
				wire.WriteString("\r\n0\r\n\r\n")
			} else {
				fmt.Fprintf(&wire, "Content-Length: %d\r\n\r\n", len(row.body))
				wire.Write(row.body)
			}
			if _, err = io.Copy(connection, &wire); err != nil {
				t.Fatal(err)
			}
			response, err := http.ReadResponse(bufio.NewReader(connection), nil)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			if response.StatusCode != row.status {
				t.Fatalf("status%d want%d", response.StatusCode, row.status)
			}
		})
	}
	if p.Snapshot().PreparationCount != 0 {
		t.Fatal("wire request leaked permit")
	}
}

func TestCanonicalKafkaSDKIntent(t *testing.T) {
	writer := &stubWriter{}
	kafka.ConfigureForTests(kafka.ProducerConfig{DeliveryMode: kafka.DeliveryModeConfirm, Topic: "exact-configured-topic"}, writer)
	receiver := NewReceiver(kafka.CurrentPipeline(), "exact-configured-topic")
	body := `{"id":" \t😀id ","site":{"id":"","domain":null},"app":{},"device":{"ip":null,"os":"","ua":"<&\u2028","lmt":-0.0},"user":{}}`
	w := response(handlerRouter(receiver), body)
	if w.Code != 200 || len(writer.messages) != 1 {
		t.Fatalf("status%d messages%d", w.Code, len(writer.messages))
	}
	record := writer.messages[0]
	if record.Topic != "exact-configured-topic" || string(record.Key) != " \t😀id " {
		t.Fatalf("original key/topic changed: %+v", record)
	}
	expect := []byte(`{"id":" \t😀id ","site":{"id":""},"app":{},"device":{"os":"","ua":"<&\u2028","lmt":0},"user":{}}`)
	semanticJSON(t, record.Value, expect)
	if bytes.Contains(record.Value, []byte("\n")) || !bytes.Contains(record.Value, []byte(`\u003c`)) {
		t.Fatalf("unexpected compact/escaping policy: %s", record.Value)
	}
	t.Logf("SDK intent key_bytes=%d value_bytes=%d", len(record.Key), len(record.Value))
}
