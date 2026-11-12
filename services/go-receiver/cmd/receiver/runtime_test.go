package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/dtkmn/go-adtech-receiver/internal/handlers"
	"github.com/dtkmn/go-adtech-receiver/internal/kafka"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
)

func TestTelemetryProfilesControlActualRouterWork(t *testing.T) {
	gin.SetMode(gin.TestMode)
	for _, profile := range []string{"controlled", "operational"} {
		t.Run(profile, func(t *testing.T) {
			var logs bytes.Buffer
			previousWriter := gin.DefaultWriter
			gin.DefaultWriter = &logs
			defer func() { gin.DefaultWriter = previousWriter }()
			previousRegisterer, previousGatherer := prometheus.DefaultRegisterer, prometheus.DefaultGatherer
			registry := prometheus.NewRegistry()
			prometheus.DefaultRegisterer, prometheus.DefaultGatherer = registry, registry
			defer func() {
				prometheus.DefaultRegisterer, prometheus.DefaultGatherer = previousRegisterer, previousGatherer
			}()
			p := kafka.NewPipeline(kafka.DeliveryModeHttpOnly, kafka.DefaultLimits(), nil)
			router := receiverRouter(handlers.NewReceiver(p, "bids"), profile)
			request := httptest.NewRequest(http.MethodPost, "/bid-request", strings.NewReader(`{"id":"x","site":{},"device":{}}`))
			request.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			if response.Code != 200 || p.Snapshot().HTTPStatuses[200] != 1 || p.Snapshot().PreparationCount != 0 {
				t.Fatalf("business/correctness path changed: status%d state%+v", response.Code, p.Snapshot())
			}
			if (logs.Len() > 0) != (profile == "operational") {
				t.Fatalf("unexpected access logging in%s: %q", profile, logs.String())
			}
			families, err := registry.Gather()
			if err != nil {
				t.Fatal(err)
			}
			if (len(families) > 0) != (profile == "operational") {
				t.Fatalf("request metrics installed in wrong profile: %s families%d", profile, len(families))
			}
			runtimeResponse := httptest.NewRecorder()
			router.ServeHTTP(runtimeResponse, httptest.NewRequest("GET", "/benchmark/runtime", nil))
			var meta map[string]any
			if err = json.Unmarshal(runtimeResponse.Body.Bytes(), &meta); err != nil {
				t.Fatal(err)
			}
			telemetry := meta["telemetry"].(map[string]any)
			for _, key := range []string{"access_log", "body_size_metrics", "request_latency_histogram"} {
				if telemetry[key] != (profile == "operational") {
					t.Fatalf("metadata%s does not match installation: %+v", key, telemetry)
				}
			}
			if meta["lane"] != "go" || meta["runtime"].(map[string]any)["heap"].(map[string]any)["max_bytes"] != nil {
				t.Fatal("incorrect runtime/heap claim")
			}
		})
	}
}

func TestTelemetryProfileRejectsUnknownValues(t *testing.T) {
	for _, raw := range []string{"", "controlled", " CONTROLLED ", "operational"} {
		if _, err := telemetryProfile(raw); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := telemetryProfile("contrlled"); err == nil {
		t.Fatal("profile typo silently accepted")
	}
}

func TestControlledDiagnosticsAreBounded(t *testing.T) {
	var output bytes.Buffer
	writer := &boundedDiagnostics{out: &output}
	for i := 0; i < 5; i++ {
		n, err := writer.Write(bytes.Repeat([]byte("x"), 5000))
		if err != nil || n != 5000 {
			t.Fatal(n, err)
		}
	}
	if output.Len() > 3*2100 || strings.Count(output.String(), "diagnostic truncated") != 3 {
		t.Fatal("diagnostic cap did not hold")
	}
}

func TestCgroupReadbackResolvesProcessLeafAndV1Fallback(t *testing.T) {
	for _, row := range []struct {
		name, groups, mounts string
		files                map[string]string
		version              int
	}{
		{"v2-native", "0::/tenant/receiver", "1 2 0:1 / /sys/fs/cgroup rw - cgroup2 cgroup rw", map[string]string{"/sys/fs/cgroup/tenant/receiver/memory.max": "805306368", "/sys/fs/cgroup/tenant/receiver/cpu.max": "200000 100000", "/sys/fs/cgroup/tenant/receiver/cpuset.cpus.effective": "2-3"}, 2},
		{"v2-namespace", "0::/", "1 2 0:1 /docker/id /sys/fs/cgroup rw - cgroup2 cgroup rw", map[string]string{"/sys/fs/cgroup/memory.max": "805306368", "/sys/fs/cgroup/cpu.max": "200000 100000", "/sys/fs/cgroup/cpuset.cpus.effective": "2-3"}, 2},
		{"v1", "1:memory:/receiver\n2:cpu,cpuacct:/receiver\n3:cpuset:/receiver", "1 2 0:1 / /sys/fs/cgroup/memory rw - cgroup cgroup rw,memory\n2 2 0:2 / /sys/fs/cgroup/cpu rw - cgroup cgroup rw,cpu,cpuacct\n3 2 0:3 / /sys/fs/cgroup/cpuset rw - cgroup cgroup rw,cpuset", map[string]string{"/sys/fs/cgroup/memory/receiver/memory.limit_in_bytes": "805306368", "/sys/fs/cgroup/cpu/receiver/cpu.cfs_quota_us": "200000", "/sys/fs/cgroup/cpu/receiver/cpu.cfs_period_us": "100000", "/sys/fs/cgroup/cpuset/receiver/cpuset.cpus": "2-3"}, 1},
	} {
		t.Run(row.name, func(t *testing.T) {
			read := func(path string) (string, error) {
				if path == "/proc/self/cgroup" {
					return row.groups, nil
				}
				if path == "/proc/self/mountinfo" {
					return row.mounts, nil
				}
				if value, ok := row.files[path]; ok {
					return value, nil
				}
				return "", errors.New("missing")
			}
			meta := readCgroup("linux", read)
			if meta["version"] != row.version || meta["memory_max_bytes"] != int64(805306368) || meta["cpu_quota_us"] != int64(200000) || meta["cpu_period_us"] != int64(100000) || meta["cpuset_cpus_effective"] != "2-3" {
				t.Fatalf("wrong leaf resource evidence: %+v", meta)
			}
		})
	}
	meta := readCgroup("darwin", func(string) (string, error) { t.Fatal("non-Linux attempted kernel read"); return "", nil })
	if meta["version"] != nil || meta["memory_max_bytes"] != nil {
		t.Fatal("fabricated kernel limit on macOS")
	}
}
