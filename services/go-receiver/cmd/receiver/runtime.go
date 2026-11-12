package main

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"runtime"
	"runtime/metrics"
	"strings"
	"sync"
	"time"

	"github.com/dtkmn/go-adtech-receiver/internal/handlers"
	"github.com/dtkmn/go-adtech-receiver/internal/kafka"
	"github.com/gin-gonic/gin"
	prometheus "github.com/zsais/go-gin-prometheus"
)

func telemetryProfile(raw string) (string, error) {
	profile := strings.ToLower(strings.TrimSpace(raw))
	if profile == "" {
		profile = "controlled"
	}
	if profile != "controlled" && profile != "operational" {
		return "", errors.New("BENCHMARK_TELEMETRY_PROFILE must be controlled or operational")
	}
	return profile, nil
}

type boundedDiagnostics struct {
	mu     sync.Mutex
	out    io.Writer
	window time.Time
	count  int
}

func (w *boundedDiagnostics) Write(data []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	n := len(data)
	if time.Since(w.window) >= time.Minute {
		w.window = time.Now()
		w.count = 0
	}
	if w.count >= 3 {
		return n, nil
	}
	w.count++
	if len(data) > 2048 {
		data = append(append([]byte{}, data[:2048]...), []byte("\n[diagnostic truncated]\n")...)
	}
	_, err := w.out.Write(data)
	return n, err
}

func receiverRouter(receiver *handlers.Receiver, profile string) *gin.Engine {
	router := gin.New()
	if profile == "operational" {
		router.Use(gin.Logger(), gin.Recovery())
	} else {
		router.Use(gin.RecoveryWithWriter(&boundedDiagnostics{out: os.Stderr}))
	}
	router.Use(receiver.PreparationMiddleware())
	if profile == "operational" {
		prometheus.NewPrometheus("gin").Use(router)
	}
	router.POST("/bid-request", receiver.ReceiveBid)
	router.GET("/benchmark/state", func(c *gin.Context) { c.JSON(http.StatusOK, receiver.Pipeline.Snapshot()) })
	router.GET("/benchmark/runtime", func(c *gin.Context) { c.JSON(http.StatusOK, runtimeSnapshot(receiver, profile)) })
	router.GET("/health", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"status": "healthy"}) })
	control := func(c *gin.Context) {
		adapter := receiver.Conformance
		tokens := c.Request.Header.Values("X-Conformance-Token")
		if adapter == nil || len(tokens) != 1 || !adapter.Authorized(tokens[0]) {
			c.Status(http.StatusNotFound)
			return
		}
		if c.Request.Method == http.MethodGet {
			c.JSON(http.StatusOK, adapter.Snapshot())
			return
		}
		defer c.Request.Body.Close()
		decoder := json.NewDecoder(http.MaxBytesReader(c.Writer, c.Request.Body, kafka.MaxBodyBytes))
		decoder.DisallowUnknownFields()
		var command kafka.ConformanceCommand
		if err := decoder.Decode(&command); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid control command"})
			return
		}
		var trailing any
		if err := decoder.Decode(&trailing); err != io.EOF {
			c.JSON(http.StatusBadRequest, gin.H{"error": "trailing control material"})
			return
		}
		result, err := adapter.Command(command)
		if err != nil {
			c.JSON(http.StatusConflict, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, result)
	}
	router.GET("/benchmark/conformance", control)
	router.POST("/benchmark/conformance", control)
	return router
}

func runtimeSnapshot(receiver *handlers.Receiver, profile string) map[string]any {
	operational := profile == "operational"
	samples := []metrics.Sample{{Name: "/gc/gomemlimit:bytes"}, {Name: "/gc/gogc:percent"}}
	metrics.Read(samples)
	heap := map[string]any{"max_bytes": nil, "gc": "Go concurrent garbage collector", "evidence": "Go has no fixed hard heap maximum; GOMEMLIMIT is a GC soft limit, not a resident/container memory cap"}
	if samples[0].Value.Kind() == metrics.KindUint64 {
		heap["soft_limit_bytes"] = samples[0].Value.Uint64()
	}
	if samples[1].Value.Kind() == metrics.KindUint64 {
		heap["gogc_percent"] = samples[1].Value.Uint64()
	}
	var requested any
	if value, present := os.LookupEnv("GOMAXPROCS"); present {
		requested = value
	}
	state := receiver.Pipeline.Snapshot()
	return map[string]any{
		"schema_version": 1, "lane": "go", "scope": "process", "delivery_mode": state.DeliveryMode,
		"telemetry": map[string]any{"profile": profile, "access_log": operational, "tracing": false, "trace_export": false, "body_size_metrics": operational, "request_latency_histogram": operational, "evidence": "Controlled installs recovery + required F2/F3 preparation/aggregate counters only; no request Prometheus middleware. Operational restores Gin access logs and gin-prometheus request metrics. Controlled panic diagnostics limited to three 2048-byte writes per minute; lifecycle logs retained."},
		"runtime":   map[string]any{"pid": os.Getpid(), "kind": "Go", "version": runtime.Version(), "http_parallelism": map[string]any{"control": "GOMAXPROCS", "requested": requested, "effective": runtime.GOMAXPROCS(0), "evidence": "Public Go scheduler readback; one receiver process. GOMAXPROCS is not total OS thread count; HTTP_SERVER_WORKERS is unsupported by this lane."}, "heap": heap, "cgroup": cgroupSnapshot()},
		"producer":  kafka.RuntimeProducer(),
		"pipeline":  map[string]any{"max_records": state.Limits.MaxRecords, "max_bytes": state.Limits.MaxBytes, "max_preparation_requests": state.Limits.MaxPreparation, "http_outcome_timeout_ms": state.HTTPTimeoutMS, "drain_timeout_ms": state.DrainTimeoutMS, "evidence": "Immutable bounds read from the live process pipeline; actual outstanding work is exported by /benchmark/state"},
	}
}
