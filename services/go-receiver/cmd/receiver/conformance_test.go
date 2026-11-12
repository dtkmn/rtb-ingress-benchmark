package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/dtkmn/go-adtech-receiver/internal/handlers"
	"github.com/dtkmn/go-adtech-receiver/internal/kafka"
	"github.com/gin-gonic/gin"
)

type faultExpectation struct {
	Status       *int              `json:"status"`
	Outcome      string            `json:"outcome"`
	Accounting   map[string]uint64 `json:"accounting"`
	Intermediate map[string]uint64 `json:"intermediate_accounting"`
	Record       *struct {
		Key   string `json:"key"`
		Value any    `json:"value"`
	} `json:"record"`
}
type faultCase struct {
	ID       string `json:"id"`
	Scenario *struct {
		Fault string `json:"fault"`
	} `json:"scenario"`
	Request struct {
		Body struct {
			Data string `json:"data"`
		} `json:"body"`
	} `json:"request"`
	Expectations map[string]faultExpectation `json:"expectations"`
}
type adapterSnapshot struct {
	State               kafka.State `json:"state"`
	Baseline            kafka.State `json:"baseline"`
	HeldAdmission       int         `json:"held_admission"`
	HeldLatePreparation int         `json:"held_late_preparation"`
	HeldDelivery        int         `json:"held_delivery"`
	DeadlineElapsed     bool        `json:"deadline_elapsed"`
	CallerReleased      bool        `json:"caller_released"`
	Events              []struct {
		Sequence  int `json:"sequence"`
		Name, Key string
	} `json:"events"`
	Records []struct {
		Topic string `json:"topic"`
		Key   string `json:"key_base64"`
		Value string `json:"value_base64"`
	} `json:"records"`
	Setup struct {
		Records      int   `json:"records"`
		Bytes        int64 `json:"bytes"`
		Preparations int   `json:"preparations"`
	} `json:"setup"`
	Mutation map[string]any `json:"mutation"`
}

func readAdapter(t *testing.T, router *gin.Engine) adapterSnapshot {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/benchmark/conformance", nil)
	req.Header.Set("X-Conformance-Token", "fixture-token")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("snapshot %d: %s", w.Code, w.Body)
	}
	var result adapterSnapshot
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	return result
}
func faultCommand(t *testing.T, router *gin.Engine, action string, fixture *faultCase) adapterSnapshot {
	t.Helper()
	data := map[string]string{"action": action}
	if fixture != nil {
		data["case_id"], data["fault"] = fixture.ID, fixture.Scenario.Fault
	}
	body, _ := json.Marshal(data)
	req := httptest.NewRequest(http.MethodPost, "/benchmark/conformance", bytes.NewReader(body))
	req.Header.Set("X-Conformance-Token", "fixture-token")
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)
	if w.Code != 200 {
		t.Fatalf("%s %d: %s", action, w.Code, w.Body)
	}
	var result adapterSnapshot
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	return result
}
func waitAdapter(t *testing.T, router *gin.Engine, predicate func(adapterSnapshot) bool) adapterSnapshot {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		result := readAdapter(t, router)
		if predicate(result) {
			return result
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("actual fixture milestone absent: %+v", readAdapter(t, router))
	return adapterSnapshot{}
}
func runFixture(router *gin.Engine, fixture faultCase, id string) <-chan *httptest.ResponseRecorder {
	result := make(chan *httptest.ResponseRecorder, 1)
	body := fixture.Request.Body.Data
	if id != "" {
		var value map[string]any
		_ = json.Unmarshal([]byte(body), &value)
		value["id"] = id
		encoded, _ := json.Marshal(value)
		body = string(encoded)
	}
	go func() {
		req := httptest.NewRequest(http.MethodPost, "/bid-request", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)
		result <- w
	}()
	return result
}
func receiveFixture(t *testing.T, channel <-chan *httptest.ResponseRecorder) *httptest.ResponseRecorder {
	t.Helper()
	select {
	case response := <-channel:
		return response
	case <-time.After(3 * time.Second):
		t.Fatal("fixture HTTP did not complete")
		return nil
	}
}
func accounting(state kafka.State) map[string]uint64 {
	return map[string]uint64{"admission_candidates": state.AdmissionCandidates, "admitted": state.Admitted, "admission_rejected": state.AdmissionRejected, "admission_undecided": state.AdmissionUndecided, "client_succeeded": state.ClientSucceeded, "client_failed": state.ClientFailed, "abandoned_unknown": state.AbandonedUnknown, "outstanding": state.Outstanding, "preparation_rejected": state.PreparationRejected}
}
func compareAccounting(t *testing.T, state, baseline kafka.State, expected map[string]uint64) {
	t.Helper()
	actual, base := accounting(state), accounting(baseline)
	for name, value := range expected {
		if actual[name]-base[name] != value {
			t.Fatalf("%s delta=%d expected=%d state=%+v baseline=%+v", name, actual[name]-base[name], value, state, baseline)
		}
	}
}

func TestConformanceEndpointGateAndActualStartupRestrictions(t *testing.T) {
	gin.SetMode(gin.ReleaseMode)
	for _, enabled := range []bool{false, true} {
		p := kafka.NewPipeline(kafka.DeliveryModeHttpOnly, kafka.DefaultLimits(), nil)
		if enabled {
			_, p = kafka.NewConformance(kafka.DeliveryModeHttpOnly, kafka.DefaultLimits(), "fixture-topic", "fixture-token")
		}
		router := receiverRouter(handlers.NewReceiver(p, "fixture-topic"), "controlled")
		for _, token := range []string{"", "wrong"} {
			req := httptest.NewRequest(http.MethodPost, "/benchmark/conformance", strings.NewReader("{"))
			req.Header.Set("X-Conformance-Token", token)
			w := httptest.NewRecorder()
			router.ServeHTTP(w, req)
			if w.Code != 404 {
				t.Fatalf("unauthorized malformed command: %d", w.Code)
			}
		}
		if !enabled && p.Conformance() != nil {
			t.Fatal("normal pipeline allocated fault ledger")
		}
		if p.Snapshot().AdmissionCandidates != 0 || p.Snapshot().PreparationCount != 0 {
			t.Fatal("unauthorized control changed pipeline")
		}
	}
	t.Setenv("BENCHMARK_DELIVERY_MODE", "confirm")
	t.Setenv("BENCHMARK_KAFKA_ACKS", "0")
	t.Setenv("BENCHMARK_CONFORMANCE", "0")
	if err := kafka.InitKafkaProducer("uncontacted.invalid:9092"); err == nil || !strings.Contains(err.Error(), "confirm") || !strings.Contains(err.Error(), "unsupported") {
		t.Fatalf("actual startup ack-zero guard: %v", err)
	}
	t.Setenv("BENCHMARK_DELIVERY_MODE", "http-only")
	t.Setenv("BENCHMARK_CONFORMANCE", "1")
	t.Setenv("BENCHMARK_CONFORMANCE_TOKEN", "")
	if err := kafka.InitKafkaProducer("uncontacted.invalid:9092"); err == nil || !strings.Contains(err.Error(), "token") {
		t.Fatalf("actual token startup guard: %v", err)
	}
}

func TestConformanceReferenceAndFixtureLedgerBounds(t *testing.T) {
	gin.SetMode(gin.ReleaseMode)
	limits := kafka.DefaultLimits()
	limits.MaxRecords = 2
	adapter, p := kafka.NewConformance(kafka.DeliveryModeEnqueue, limits, "fixture-topic", "fixture-token")
	if _, err := adapter.Command(kafka.ConformanceCommand{Action: "configure", CaseID: "small", Fault: "backlog_record_limit"}); err == nil {
		t.Fatal("smaller reference capacities accepted")
	}
	if p.Snapshot().AdmissionCandidates != 0 {
		t.Fatal("rejected configure performed setup")
	}
	_, p = kafka.NewConformance(kafka.DeliveryModeEnqueue, kafka.DefaultLimits(), "fixture-topic", "fixture-token")
	router := receiverRouter(handlers.NewReceiver(p, "fixture-topic"), "controlled")
	fixture := faultCase{ID: "bounded"}
	fixture.Scenario = &struct {
		Fault string `json:"fault"`
	}{Fault: "confirm_ack_zero"}
	fixture.Request.Body.Data = `{"id":"x","site":{},"device":{}}`
	faultCommand(t, router, "configure", &fixture)
	for index, expected := range []int{200, 200, 503} {
		response := receiveFixture(t, runFixture(router, fixture, string(rune('a'+index))))
		if response.Code != expected {
			t.Fatalf("attempt %d: %d", index, response.Code)
		}
	}
	faultCommand(t, router, "cleanup", nil)
	if len(readAdapter(t, router).Records) != 2 || p.Snapshot().AdmissionRejected != 1 {
		t.Fatal("fixture ledger not bounded by real rejection")
	}
}

func TestAllConformanceFaultsThroughActualHandlers(t *testing.T) {
	gin.SetMode(gin.ReleaseMode)
	data, err := os.ReadFile("../../../../tests/conformance/corpus.json")
	if err != nil {
		t.Fatal(err)
	}
	var corpus struct {
		Cases []faultCase `json:"cases"`
	}
	if err := json.Unmarshal(data, &corpus); err != nil {
		t.Fatal(err)
	}
	var fixtures []faultCase
	for _, fixture := range corpus.Cases {
		if fixture.Scenario != nil {
			fixtures = append(fixtures, fixture)
		}
	}
	sort.SliceStable(fixtures, func(i, j int) bool {
		return fixtures[i].Scenario.Fault != "drain_deadline_unsettled" && fixtures[j].Scenario.Fault == "drain_deadline_unsettled"
	})
	if len(fixtures) != 18 {
		t.Fatalf("fault corpus changed: %d", len(fixtures))
	}
	for _, mode := range []string{"http-only", "confirm", "enqueue"} {
		t.Run(mode, func(t *testing.T) {
			limits := kafka.DefaultLimits()
			limits.HTTPTimeout, limits.DrainTimeout = 150*time.Millisecond, 150*time.Millisecond
			_, p := kafka.NewConformance(mode, limits, "configured-topic", "fixture-token")
			router := receiverRouter(handlers.NewReceiver(p, "configured-topic"), "controlled")
			for _, fixture := range fixtures {
				if fixture.Expectations[mode].Outcome == "unsupported" {
					continue
				}
				t.Run(fixture.Scenario.Fault, func(t *testing.T) {
					fault, expected := fixture.Scenario.Fault, fixture.Expectations[mode]
					initial := faultCommand(t, router, "configure", &fixture)
					baseline := initial.Baseline
					if fault == "final_slot_race" && mode != "http-only" {
						if initial.Setup.Records != 1023 || initial.State.Outstanding != 1023 {
							t.Fatal("race lacks default setup")
						}
						left, right := runFixture(router, fixture, "race-a"), runFixture(router, fixture, "race-b")
						waitAdapter(t, router, func(s adapterSnapshot) bool { return s.HeldAdmission == 2 })
						faultCommand(t, router, "release_admission", nil)
						waitAdapter(t, router, func(s adapterSnapshot) bool { return s.HeldDelivery == 1 })
						faultCommand(t, router, "release_delivery", nil)
						first, second := receiveFixture(t, left), receiveFixture(t, right)
						if !((first.Code == 200 && second.Code == 503) || (first.Code == 503 && second.Code == 200)) {
							t.Fatalf("race outcomes %d/%d", first.Code, second.Code)
						}
						final := readAdapter(t, router)
						winner := "race-a"
						if second.Code == 200 {
							winner = "race-b"
						}
						key, _ := base64.StdEncoding.DecodeString(final.Records[0].Key)
						if string(key) != winner || final.State.Outstanding != 1023 {
							t.Fatal("observed winner/setup ownership differs")
						}
						loser := final.State
						loser.AdmissionCandidates--
						loser.Admitted--
						loser.ClientSucceeded--
						compareAccounting(t, loser, baseline, expected.Accounting)
					} else {
						if mode != "http-only" && fault == "backlog_record_limit" && initial.Setup.Records != 1024 {
							t.Fatal("record setup is not full default")
						}
						if mode != "http-only" && fault == "backlog_byte_limit" && (initial.Setup.Bytes != 16777215 || initial.Setup.Records >= 1024) {
							t.Fatal("byte setup differs")
						}
						if fault == "preparation_limit" && initial.State.PreparationCount != 128 {
							t.Fatal("prep setup is not full default")
						}
						response := runFixture(router, fixture, "")
						var actual *httptest.ResponseRecorder
						if mode != "http-only" && (fault == "held_admission_then_success" || fault == "held_admission_then_rejection" || fault == "admission_deadline_then_late_receipt") {
							held := waitAdapter(t, router, func(s adapterSnapshot) bool { return s.HeldAdmission == 1 })
							intermediate := expected.Intermediate
							if fault == "held_admission_then_success" {
								intermediate = map[string]uint64{"admission_candidates": 1, "admission_undecided": 1, "admitted": 0}
							}
							compareAccounting(t, held.State, baseline, intermediate)
							select {
							case <-response:
								t.Fatal("HTTP returned before owned admission")
							default:
							}
							if fault == "admission_deadline_then_late_receipt" {
								actual = receiveFixture(t, response)
								if p.Snapshot().PreparationCount != 1 {
									t.Fatal("HTTP deadline freed live preparation")
								}
								expired := readAdapter(t, router)
								if expired.HeldAdmission != 0 || expired.HeldLatePreparation != 1 {
									t.Fatal("expired candidate was reported as undecided admission instead of retained late work")
								}
								faultCommand(t, router, "release_admission", nil)
							} else {
								action := "release_admission"
								if fault == "held_admission_then_rejection" {
									action = "reject_admission"
								}
								faultCommand(t, router, action, nil)
							}
						}
						if mode != "http-only" && (fault == "held_admission_then_success" || fault == "held_delivery_then_success" || fault == "delivery_failure_after_receipt" || fault == "response_deadline_then_late_success" || fault == "duplicate_completion_callback" || fault == "source_mutation_after_receipt" || fault == "drain_deadline_unsettled") {
							held := waitAdapter(t, router, func(s adapterSnapshot) bool { return s.HeldDelivery == 1 && s.CallerReleased })
							if held.State.PreparationCount != 0 {
								t.Fatal("caller preparation retained across confirm wait")
							}
							compareAccounting(t, held.State, baseline, expected.Intermediate)
							if mode == "confirm" {
								select {
								case <-response:
									t.Fatal("confirm returned before client outcome")
								default:
								}
							} else {
								actual = receiveFixture(t, response)
								if actual.Code != 200 {
									t.Fatal("enqueue lacks owned receipt success")
								}
							}
							if fault == "source_mutation_after_receipt" {
								if len(held.Records) != 0 {
									t.Fatal("mutation dispatch not held")
								}
								mutated := faultCommand(t, router, "mutate_source", nil)
								if mutated.Mutation["before_value_base64"] == mutated.Mutation["after_value_base64"] {
									t.Fatal("original caller bytes not mutated")
								}
							}
							if fault == "response_deadline_then_late_success" {
								waitAdapter(t, router, func(s adapterSnapshot) bool { return s.DeadlineElapsed })
								if mode == "confirm" {
									actual = receiveFixture(t, response)
								}
								if p.Snapshot().Outstanding != 1 {
									t.Fatal("deadline freed owned receipt")
								}
							}
							action := "release_delivery"
							if fault == "delivery_failure_after_receipt" {
								action = "fail_delivery"
							}
							if fault == "drain_deadline_unsettled" {
								action = "drain"
							}
							faultCommand(t, router, action, nil)
							if fault == "duplicate_completion_callback" {
								before := p.Snapshot()
								faultCommand(t, router, "duplicate_completion", nil)
								if !reflect.DeepEqual(before, p.Snapshot()) {
									t.Fatal("duplicate changed actual settlement")
								}
							}
						}
						if actual == nil {
							actual = receiveFixture(t, response)
						}
						if actual.Code != *expected.Status {
							t.Fatalf("HTTP %d expected %d: %s", actual.Code, *expected.Status, actual.Body)
						}
						if actual.Code == 200 && actual.Body.String() != `{"status":"accepted"}` {
							t.Fatalf("accepted contract changed: %s", actual.Body)
						}
						final := waitAdapter(t, router, func(s adapterSnapshot) bool {
							return s.State.AdmissionUndecided == 0 && s.State.Outstanding == baseline.Outstanding+expected.Accounting["outstanding"]
						})
						compareAccounting(t, final.State, baseline, expected.Accounting)
						if expected.Record != nil {
							if len(final.Records) != 1 {
								t.Fatalf("dispatch count %d", len(final.Records))
							}
							key, _ := base64.StdEncoding.DecodeString(final.Records[0].Key)
							value, _ := base64.StdEncoding.DecodeString(final.Records[0].Value)
							var canonical any
							_ = json.Unmarshal(value, &canonical)
							if string(key) != expected.Record.Key || !reflect.DeepEqual(canonical, expected.Record.Value) || final.Records[0].Topic != "configured-topic" {
								t.Fatalf("owned dispatch differs: key=%q value=%s", key, value)
							}
						} else if len(final.Records) != 0 {
							t.Fatal("bypassed/rejected work dispatched")
						}
					}
					if fault != "drain_deadline_unsettled" || mode == "http-only" {
						faultCommand(t, router, "cleanup", nil)
						state := p.Snapshot()
						if state.Outstanding != 0 || state.PreparationCount != 0 {
							t.Fatal("actual cleanup did not settle")
						}
					}
				})
			}
		})
	}
}
