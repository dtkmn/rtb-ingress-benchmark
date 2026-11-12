package kafka

import (
	"context"
	"errors"
	"log"
	"os"
	"strings"
	"sync"
	"time"

	kgo "github.com/segmentio/kafka-go"
)

var KafkaWriter MessageWriter
var runtimeMu sync.RWMutex
var runtime = NewPipeline(DeliveryModeConfirm, DefaultLimits(), nil)

// kafka-go allocates BatchSize message entries for each batch, even when the
// byte limit fills it with one record. Its documented default of 100 keeps this
// bookkeeping bounded; no batch can use more records than our entire pipeline.
const maxSDKBatchRecords = 100

func sdkBatchRecordLimit(limits Limits) int {
	return min(limits.MaxRecords, maxSDKBatchRecords)
}

func sdkBatchTimeout(milliseconds int) time.Duration {
	if milliseconds == 0 {
		// A literal zero selects kafka-go's one-second default, not no linger.
		return time.Nanosecond
	}
	return time.Duration(milliseconds) * time.Millisecond
}

func CurrentPipeline() *Pipeline { runtimeMu.RLock(); defer runtimeMu.RUnlock(); return runtime }
func setPipeline(p *Pipeline)    { runtimeMu.Lock(); runtime = p; runtimeMu.Unlock() }

var producerConfig = ProducerConfig{
	Topic:            "bids",
	DeliveryMode:     DeliveryModeConfirm,
	RequiredAcks:     kgo.RequireOne,
	BatchTimeoutMs:   10,
	BatchBytes:       131072,
	RequestTimeoutMs: 5000,
	Retries:          5,
	RetryBackoffMs:   100,
}

// InitKafkaProducer sets up the global Kafka writer.
func InitKafkaProducer(kafkaURL string) error {
	producerConfig = LoadProducerConfigFromEnv()
	limits := LoadLimits()
	if producerConfig.DeliveryMode == DeliveryModeConfirm && producerConfig.RequiredAcks == kgo.RequireNone {
		return errors.New("confirm with acknowledgements=0 is unsupported")
	}
	if os.Getenv("BENCHMARK_CONFORMANCE") == "1" {
		token := os.Getenv("BENCHMARK_CONFORMANCE_TOKEN")
		if strings.TrimSpace(token) == "" {
			return errors.New("conformance requires a nonempty token")
		}
		_, pipeline := NewConformance(producerConfig.DeliveryMode, limits, producerConfig.Topic, token)
		KafkaWriter = nil
		setPipeline(pipeline)
		log.Print("Token-gated conformance transport enabled; no Kafka SDK writer constructed")
		return nil
	}
	if !producerConfig.UsesKafka() {
		log.Printf("BENCHMARK_DELIVERY_MODE=%s; skipping Kafka producer initialization", producerConfig.DeliveryMode)
		KafkaWriter = nil
		setPipeline(NewPipeline(producerConfig.DeliveryMode, limits, nil))
		return nil
	}

	log.Printf(
		"Initializing Kafka producer for topic %q at %s (delivery_mode=%s, required_acks=%d, async=%t, batch_timeout_ms=%d, batch_bytes=%d, sdk_batch_max_records=%d, request_timeout_ms=%d, retries=%d, retry_backoff_ms=%d)",
		producerConfig.Topic,
		kafkaURL,
		producerConfig.DeliveryMode,
		producerConfig.RequiredAcks,
		producerConfig.AsyncWrites(),
		producerConfig.BatchTimeoutMs,
		producerConfig.BatchBytes,
		sdkBatchRecordLimit(limits),
		producerConfig.RequestTimeoutMs,
		producerConfig.Retries,
		producerConfig.RetryBackoffMs,
	)

	maxAttempts := producerConfig.Retries + 1
	if maxAttempts < 1 {
		maxAttempts = 1
	}
	retryBackoff := time.Duration(producerConfig.RetryBackoffMs) * time.Millisecond

	transportCtx, cancel := context.WithCancel(context.Background())
	transport := &kgo.Transport{Context: transportCtx}
	writer := &kgo.Writer{
		Addr:                   kgo.TCP(kafkaURL),
		Balancer:               &kgo.LeastBytes{},
		Async:                  true,
		Transport:              &cancelTransport{inner: transport, ctx: transportCtx},
		Completion:             completeWriterMessages,
		AllowAutoTopicCreation: false,
		MaxAttempts:            maxAttempts,
		WriteBackoffMin:        retryBackoff,
		WriteBackoffMax:        retryBackoff,

		// Performance Tuning
		BatchTimeout: sdkBatchTimeout(producerConfig.BatchTimeoutMs),
		BatchSize:    sdkBatchRecordLimit(limits),
		RequiredAcks: producerConfig.RequiredAcks,
		BatchBytes:   producerConfig.BatchBytes,
		ReadTimeout:  time.Duration(producerConfig.RequestTimeoutMs) * time.Millisecond,
		WriteTimeout: time.Duration(producerConfig.RequestTimeoutMs) * time.Millisecond,
	}
	KafkaWriter = writer
	pipeline := NewPipeline(producerConfig.DeliveryMode, limits, &kafkaDispatcher{writer: writer, cancel: cancel, transport: transport, closed: make(chan struct{})})
	pipeline.state.SDKBatchMaxRecords = writer.BatchSize
	setPipeline(pipeline)
	return nil
}

func DeliveryMode() string {
	return CurrentPipeline().Mode()
}

func Topic() string { return producerConfig.Topic }
