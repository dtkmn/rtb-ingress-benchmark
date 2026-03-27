package kafka

import (
	"context"
	"log"
	"os"
	"strconv"
	"strings"

	kgo "github.com/segmentio/kafka-go"
)

const (
	DeliveryModeConfirm  = "confirm"
	DeliveryModeEnqueue  = "enqueue"
	DeliveryModeHttpOnly = "http-only"
)

type MessageWriter interface {
	WriteMessages(context.Context, ...kgo.Message) error
}

type ProducerConfig struct {
	Topic            string
	DeliveryMode     string
	RequiredAcks     kgo.RequiredAcks
	BatchTimeoutMs   int
	BatchBytes       int64
	RequestTimeoutMs int
	Retries          int
	RetryBackoffMs   int
}

func LoadProducerConfigFromEnv() ProducerConfig {
	deliveryMode := strings.ToLower(strings.TrimSpace(os.Getenv("BENCHMARK_DELIVERY_MODE")))
	if deliveryMode == "" {
		deliveryMode = DeliveryModeConfirm
	}
	if deliveryMode != DeliveryModeConfirm && deliveryMode != DeliveryModeEnqueue && deliveryMode != DeliveryModeHttpOnly {
		log.Printf("Unknown BENCHMARK_DELIVERY_MODE=%q, defaulting to %q", deliveryMode, DeliveryModeConfirm)
		deliveryMode = DeliveryModeConfirm
	}

	acks := parseRequiredAcks(os.Getenv("BENCHMARK_KAFKA_ACKS"))
	return ProducerConfig{
		Topic:            parseTopic(os.Getenv("BENCHMARK_KAFKA_TOPIC")),
		DeliveryMode:     deliveryMode,
		RequiredAcks:     acks,
		BatchTimeoutMs:   parsePositiveInt("BENCHMARK_KAFKA_LINGER_MS", 10),
		BatchBytes:       int64(parsePositiveInt("BENCHMARK_KAFKA_BATCH_BYTES", 131072)),
		RequestTimeoutMs: parsePositiveInt("BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS", 5000),
		Retries:          parseNonNegativeInt("BENCHMARK_KAFKA_RETRIES", 5),
		RetryBackoffMs:   parseNonNegativeInt("BENCHMARK_KAFKA_RETRY_BACKOFF_MS", 100),
	}
}

func (c ProducerConfig) AsyncWrites() bool {
	return c.DeliveryMode == DeliveryModeEnqueue
}

func (c ProducerConfig) UsesKafka() bool {
	return c.DeliveryMode != DeliveryModeHttpOnly
}

// ConfigureForTests overrides package globals so handler tests can exercise
// mode-dependent behavior without creating a real Kafka client.
func ConfigureForTests(config ProducerConfig, writer MessageWriter) {
	producerConfig = config
	KafkaWriter = writer
}

func parseRequiredAcks(raw string) kgo.RequiredAcks {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "", "1", "leader":
		return kgo.RequireOne
	case "0", "none":
		return kgo.RequireNone
	case "-1", "all":
		return kgo.RequireAll
	default:
		log.Printf("Unknown BENCHMARK_KAFKA_ACKS=%q, defaulting to leader acknowledgements", raw)
		return kgo.RequireOne
	}
}

func parsePositiveInt(envName string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(envName))
	if raw == "" {
		return fallback
	}

	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		log.Printf("Ignoring invalid %s=%q; defaulting to %d", envName, raw, fallback)
		return fallback
	}

	return value
}

func parseNonNegativeInt(envName string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(envName))
	if raw == "" {
		return fallback
	}

	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 {
		log.Printf("Ignoring invalid %s=%q; defaulting to %d", envName, raw, fallback)
		return fallback
	}

	return value
}

func parseTopic(raw string) string {
	topic := strings.TrimSpace(raw)
	if topic == "" {
		return "bids"
	}
	return topic
}
