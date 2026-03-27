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
