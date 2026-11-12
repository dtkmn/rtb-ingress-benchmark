package kafka

import (
	"testing"

	kgo "github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
)

func TestParseRequiredAcks(t *testing.T) {
	t.Run("defaults to leader ack", func(t *testing.T) {
		assert.Equal(t, kgo.RequireOne, parseRequiredAcks(""))
	})

	t.Run("supports none", func(t *testing.T) {
		assert.Equal(t, kgo.RequireNone, parseRequiredAcks("0"))
		assert.Equal(t, kgo.RequireNone, parseRequiredAcks("none"))
	})

	t.Run("supports all", func(t *testing.T) {
		assert.Equal(t, kgo.RequireAll, parseRequiredAcks("all"))
		assert.Equal(t, kgo.RequireAll, parseRequiredAcks("-1"))
	})
}

func TestParsePositiveInt(t *testing.T) {
	t.Setenv("BENCHMARK_KAFKA_BATCH_BYTES", "131072")
	assert.Equal(t, 131072, parsePositiveInt("BENCHMARK_KAFKA_BATCH_BYTES", 42))

	t.Setenv("BENCHMARK_KAFKA_BATCH_BYTES", "weird")
	assert.Equal(t, 42, parsePositiveInt("BENCHMARK_KAFKA_BATCH_BYTES", 42))
}

func TestLoadProducerConfigFromEnv(t *testing.T) {
	t.Setenv("BENCHMARK_KAFKA_TOPIC", "bench-bids")
	t.Setenv("BENCHMARK_KAFKA_RETRIES", "7")
	t.Setenv("BENCHMARK_KAFKA_RETRY_BACKOFF_MS", "250")

	config := LoadProducerConfigFromEnv()

	assert.Equal(t, "bench-bids", config.Topic)
	assert.Equal(t, 7, config.Retries)
	assert.Equal(t, 250, config.RetryBackoffMs)
}

func TestHTTPOnlyDoesNotInitializeKafka(t *testing.T) {
	previousWriter, previousConfig, previousPipeline := KafkaWriter, producerConfig, CurrentPipeline()
	defer func() {
		KafkaWriter, producerConfig = previousWriter, previousConfig
		setPipeline(previousPipeline)
	}()
	t.Setenv("BENCHMARK_DELIVERY_MODE", DeliveryModeHttpOnly)
	t.Setenv("BENCHMARK_KAFKA_ACKS", "0")
	if err := InitKafkaProducer("uncontacted.invalid:9092"); err != nil {
		t.Fatal(err)
	}
	if KafkaWriter != nil || CurrentPipeline().dispatcher != nil || CurrentPipeline().Mode() != DeliveryModeHttpOnly {
		t.Fatal("http-only initialized a Kafka client or dispatcher")
	}
	if CurrentPipeline().Snapshot().AdmissionCandidates != 0 {
		t.Fatal("http-only initialization formed a Kafka candidate")
	}
}
