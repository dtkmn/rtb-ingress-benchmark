package kafka

import (
	"os"
	"runtime/debug"
	"time"

	kgo "github.com/segmentio/kafka-go"
)

func requested(name string, fallback any) any {
	if value, present := os.LookupEnv(name); present {
		return value
	}
	return fallback
}

func runtimeControl(status string, request, applied, effective any, evidence string) map[string]any {
	return map[string]any{"status": status, "requested": request, "applied": applied, "effective": effective, "evidence": evidence}
}

func linkedClientVersion() (any, string) {
	info, ok := debug.ReadBuildInfo()
	if ok {
		for _, module := range info.Deps {
			if module.Path == "github.com/segmentio/kafka-go" {
				if module.Replace != nil {
					module = module.Replace
				}
				if module.Version != "" && module.Version != "(devel)" {
					return module.Version, "Actual linked Kafka client dependency from runtime/debug.ReadBuildInfo; replacement module used if present"
				}
				return nil, "Linked Kafka client is a development/local replacement with no embedded version"
			}
		}
	}
	return nil, "Kafka client dependency version unavailable in this binary's embedded Go build information"
}

// RuntimeProducer reports immutable public writer configuration without calling
// Stats(), which consumes the client's interval counters. No network I/O occurs.
func RuntimeProducer() map[string]any {
	controls := map[string]any{}
	w, constructed := KafkaWriter.(*kgo.Writer)
	instances := 0
	if constructed {
		instances = 1
	}
	values := []struct {
		name, env string
		fallback  any
	}{
		{"acks", "BENCHMARK_KAFKA_ACKS", int(producerConfig.RequiredAcks)},
		{"linger_ms", "BENCHMARK_KAFKA_LINGER_MS", producerConfig.BatchTimeoutMs},
		{"batch_bytes", "BENCHMARK_KAFKA_BATCH_BYTES", producerConfig.BatchBytes},
		{"retries", "BENCHMARK_KAFKA_RETRIES", producerConfig.Retries},
		{"retry_backoff_ms", "BENCHMARK_KAFKA_RETRY_BACKOFF_MS", producerConfig.RetryBackoffMs},
		{"request_timeout_ms", "BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS", producerConfig.RequestTimeoutMs},
		{"producer_pool_size", "BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", 1},
	}
	for _, value := range values {
		controls[value.name] = runtimeControl("not_applicable", requested(value.env, value.fallback), nil, nil, "No concrete Kafka writer constructed in this process")
	}
	if constructed {
		ms := func(d time.Duration) float64 { return float64(d) / float64(time.Millisecond) }
		control := func(name, env string, fallback, value any, evidence string) {
			controls[name] = runtimeControl("applied", requested(env, fallback), value, value, evidence)
		}
		control("acks", "BENCHMARK_KAFKA_ACKS", int(producerConfig.RequiredAcks), int(w.RequiredAcks), "Kafka Writer.RequiredAcks public field")
		control("linger_ms", "BENCHMARK_KAFKA_LINGER_MS", producerConfig.BatchTimeoutMs, ms(w.BatchTimeout), "Kafka Writer.BatchTimeout public field, milliseconds")
		if producerConfig.BatchTimeoutMs == 0 {
			controls["linger_ms"] = runtimeControl("unsupported", requested("BENCHMARK_KAFKA_LINGER_MS", 0), ms(w.BatchTimeout), ms(w.BatchTimeout), "Exact zero unsupported: Writer uses a one-second default for literal zero; requested zero maps to a disclosed 1ns timer, preserving SDK record/byte batching")
		}
		control("batch_bytes", "BENCHMARK_KAFKA_BATCH_BYTES", producerConfig.BatchBytes, w.BatchBytes, "Kafka Writer.BatchBytes public field")
		control("retries", "BENCHMARK_KAFKA_RETRIES", producerConfig.Retries, w.MaxAttempts-1, "Derived from Writer.MaxAttempts public field: retry count is maximum attempts minus the first attempt")
		control("request_timeout_ms", "BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS", producerConfig.RequestTimeoutMs, ms(w.WriteTimeout), "Writer.ReadTimeout and WriteTimeout are configured identically; these are socket/client timeouts, not the HTTP outcome deadline")
		control("retry_backoff_ms", "BENCHMARK_KAFKA_RETRY_BACKOFF_MS", producerConfig.RetryBackoffMs, ms(w.WriteBackoffMin), "Writer.WriteBackoffMin/Max public fields are configured identically")
		if w.WriteBackoffMin <= 0 {
			controls["retry_backoff_ms"] = runtimeControl("applied", requested("BENCHMARK_KAFKA_RETRY_BACKOFF_MS", 0), 0, nil, "Public zero fields invoke SDK defaults (100ms minimum/1000ms maximum in pinned source); no public effective getter, so zero is not effective zero")
		}
		controls["producer_pool_size"] = runtimeControl("unsupported", requested("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", 1), nil, nil, "One process-level Writer is constructed; this lane does not implement an application producer pool")
		for name, value := range map[string]any{"batch_timeout_ns": w.BatchTimeout.Nanoseconds(), "batch_max_records": w.BatchSize, "max_attempts": w.MaxAttempts, "async": w.Async, "write_backoff_min_ms": ms(w.WriteBackoffMin), "write_backoff_max_ms": ms(w.WriteBackoffMax), "read_timeout_ms": ms(w.ReadTimeout), "write_timeout_ms": ms(w.WriteTimeout), "compression": int(w.Compression)} {
			var effective any = value
			if (name == "write_backoff_min_ms" && w.WriteBackoffMin <= 0) || (name == "write_backoff_max_ms" && w.WriteBackoffMax <= 0) {
				effective = nil
			}
			controls[name] = runtimeControl("applied", nil, value, effective, "Read from the concrete Kafka Writer public field; nonpositive backoff fields select SDK defaults")
		}
	}
	version, versionEvidence := linkedClientVersion()
	metadata := map[string]any{"client": "segmentio/kafka-go", "version": version, "version_evidence": versionEvidence, "instances": instances, "controls": controls, "evidence": "Actual concrete Writer count in this process; injected test adapters are not Kafka writers. Config fields are immutable after startup."}
	if CurrentPipeline().Conformance() != nil {
		metadata["conformance_transport"] = true
		metadata["evidence"] = "Token-gated receiver conformance transport; zero actual Kafka writers. Linked SDK identity is not SDK construction or broker/durability proof."
	}
	return metadata
}
