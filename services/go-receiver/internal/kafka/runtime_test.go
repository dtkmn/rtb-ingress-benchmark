package kafka

import (
	"context"
	"testing"
	"time"

	kgo "github.com/segmentio/kafka-go"
)

func TestZeroLingerMappingAndActualWriterRuntime(t *testing.T) {
	previousWriter, previousConfig, previousPipeline := KafkaWriter, producerConfig, CurrentPipeline()
	defer func() { KafkaWriter, producerConfig = previousWriter, previousConfig; setPipeline(previousPipeline) }()
	t.Setenv("BENCHMARK_DELIVERY_MODE", DeliveryModeConfirm)
	t.Setenv("BENCHMARK_KAFKA_ACKS", "1")
	t.Setenv("BENCHMARK_KAFKA_LINGER_MS", "0")
	t.Setenv("BENCHMARK_KAFKA_BATCH_BYTES", "16384")
	t.Setenv("BENCHMARK_KAFKA_RETRIES", "0")
	t.Setenv("BENCHMARK_KAFKA_RETRY_BACKOFF_MS", "100")
	t.Setenv("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", "2")
	if err := InitKafkaProducer("uncontacted.invalid:9092"); err != nil {
		t.Fatal(err)
	}
	w := KafkaWriter.(*kgo.Writer)
	if w.BatchTimeout != time.Nanosecond || w.BatchSize != 100 || w.BatchBytes != 16384 || w.MaxAttempts != 1 {
		t.Fatalf("SDK constructor mismatch: %+v", w)
	}
	meta := RuntimeProducer()
	controls := meta["controls"].(map[string]any)
	linger := controls["linger_ms"].(map[string]any)
	if meta["instances"] != 1 || linger["status"] != "unsupported" || linger["requested"] != "0" || linger["applied"] != 0.000001 || linger["effective"] != 0.000001 {
		t.Fatalf("zero linger approximation hidden: %+v", meta)
	}
	if controls["producer_pool_size"].(map[string]any)["requested"] != "2" {
		t.Fatal("unsupported requested pool discarded")
	}
	if err := CurrentPipeline().Shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	for _, raw := range []string{"-1", "invalid", ""} {
		t.Setenv("BENCHMARK_KAFKA_LINGER_MS", raw)
		if config := LoadProducerConfigFromEnv(); config.BatchTimeoutMs != 10 {
			t.Fatalf("invalid linger%q did not retain fallback", raw)
		}
	}
	if sdkBatchTimeout(10) != 10*time.Millisecond {
		t.Fatal("nonzero linger changed")
	}
}

func TestHTTPOnlyRuntimeDoesNotClaimProducerControls(t *testing.T) {
	previousWriter, previousConfig, previousPipeline := KafkaWriter, producerConfig, CurrentPipeline()
	defer func() { KafkaWriter, producerConfig = previousWriter, previousConfig; setPipeline(previousPipeline) }()
	t.Setenv("BENCHMARK_DELIVERY_MODE", DeliveryModeHttpOnly)
	t.Setenv("BENCHMARK_KAFKA_LINGER_MS", "0")
	t.Setenv("BENCHMARK_KAFKA_PRODUCER_POOL_SIZE", "3")
	if err := InitKafkaProducer("uncontacted.invalid:9092"); err != nil {
		t.Fatal(err)
	}
	meta := RuntimeProducer()
	if meta["instances"] != 0 {
		t.Fatal("HTTP-only constructed a writer")
	}
	for _, control := range meta["controls"].(map[string]any) {
		value := control.(map[string]any)
		if value["status"] != "not_applicable" || value["applied"] != nil || value["effective"] != nil {
			t.Fatalf("HTTP-only fabricated control: %+v", value)
		}
	}
}
