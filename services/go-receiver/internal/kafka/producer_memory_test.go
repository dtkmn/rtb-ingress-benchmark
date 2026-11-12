package kafka

import (
	"bytes"
	"context"
	"fmt"
	"net"
	goruntime "runtime"
	"sync"
	"testing"
	"time"
	"unsafe"

	kgo "github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/protocol/metadata"
	"github.com/segmentio/kafka-go/protocol/produce"
)

func TestSDKBatchBookkeepingIsBoundedByPipelineAndSafeCount(t *testing.T) {
	for _, records := range []int{1, 2, 99, 100, 1024, 65536} {
		limit := sdkBatchRecordLimit(Limits{MaxRecords: records})
		if limit < 1 || limit > records || limit > 100 {
			t.Fatalf("pipeline=%d, SDK batch record capacity=%d", records, limit)
		}
	}
	// A byte-limited backlog can contain one batch per outstanding record.
	// kafka-go preallocates the full Message slice capacity for each batch.
	limits := DefaultLimits()
	metadataBytes := uintptr(limits.MaxRecords*sdkBatchRecordLimit(limits)) * unsafe.Sizeof(kgo.Message{})
	if metadataBytes > 32<<20 {
		t.Fatalf("default worst-case batch Message arrays require %d bytes", metadataBytes)
	}
	t.Logf("Message bytes=%d, default batch arrays at 1024 one-record batches=%d bytes", unsafe.Sizeof(kgo.Message{}), metadataBytes)
}

type blockedProduceTransport struct {
	started     chan struct{}
	release     chan struct{}
	startOnce   sync.Once
	releaseOnce sync.Once
}

func (b *blockedProduceTransport) unblock() { b.releaseOnce.Do(func() { close(b.release) }) }

func (b *blockedProduceTransport) RoundTrip(ctx context.Context, _ net.Addr, request kgo.Request) (kgo.Response, error) {
	switch request := request.(type) {
	case *metadata.Request:
		return &metadata.Response{Topics: []metadata.ResponseTopic{{
			Name: request.TopicNames[0], Partitions: []metadata.ResponsePartition{{PartitionIndex: 0}},
		}}}, nil
	case *produce.Request:
		b.startOnce.Do(func() { close(b.started) })
		select {
		case <-b.release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		response := &produce.Response{}
		for _, topic := range request.Topics {
			result := produce.ResponseTopic{Topic: topic.Topic}
			for _, partition := range topic.Partitions {
				result.Partitions = append(result.Partitions, produce.ResponsePartition{Partition: partition.Partition})
			}
			response.Topics = append(response.Topics, result)
		}
		return response, nil
	default:
		return nil, fmt.Errorf("unexpected SDK request %T", request)
	}
}

func TestRealAsyncWriterByteLimitedBacklogHasBoundedBookkeeping(t *testing.T) {
	t.Setenv("BENCHMARK_DELIVERY_MODE", DeliveryModeEnqueue)
	t.Setenv("BENCHMARK_KAFKA_ACKS", "1")
	t.Setenv("BENCHMARK_PIPELINE_MAX_RECORDS", "1024")
	t.Setenv("BENCHMARK_PIPELINE_MAX_BYTES", "16777216")
	t.Setenv("BENCHMARK_KAFKA_BATCH_BYTES", "16384")
	t.Setenv("BENCHMARK_KAFKA_LINGER_MS", "10")
	t.Setenv("BENCHMARK_KAFKA_REQUEST_TIMEOUT_MS", "5000")
	t.Setenv("BENCHMARK_DRAIN_TIMEOUT_MS", "1000")
	oldConfig, oldWriter, oldPipeline := producerConfig, KafkaWriter, CurrentPipeline()
	if err := InitKafkaProducer("unused:9092"); err != nil {
		t.Fatal(err)
	}
	pipeline := CurrentPipeline()
	writer := KafkaWriter.(*kgo.Writer)
	blocked := &blockedProduceTransport{started: make(chan struct{}), release: make(chan struct{})}
	// The actual kafka-go batching and Completion path run; this replaces only
	// wire I/O, so the test never opens a socket or uses a Kafka broker.
	writer.Transport = blocked
	t.Cleanup(func() {
		blocked.unblock()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := pipeline.Shutdown(ctx); err != nil {
			t.Errorf("SDK test drain failed: %v", err)
		}
		producerConfig, KafkaWriter = oldConfig, oldWriter
		setPipeline(oldPipeline)
	})
	if writer.BatchSize != 100 || pipeline.Snapshot().SDKBatchMaxRecords != 100 {
		t.Fatalf("production writer/snapshot batch record capacity: %d/%d", writer.BatchSize, pipeline.Snapshot().SDKBatchMaxRecords)
	}
	if writer.BatchBytes != 16384 || writer.BatchTimeout != 10*time.Millisecond {
		t.Fatalf("byte/timer controls changed: %d/%s", writer.BatchBytes, writer.BatchTimeout)
	}
	goruntime.GC()
	var before, held goruntime.MemStats
	goruntime.ReadMemStats(&before)
	const records = 16
	value := bytes.Repeat([]byte{'a'}, 12<<10)
	for i := 0; i < records; i++ {
		if _, err := pipeline.BeginCandidate(context.Background()).Commit(Record{Topic: "bounded", Key: []byte("id"), Value: value}); err != nil {
			t.Fatal(err)
		}
	}
	pipeline.dispatches.Wait() // Every async WriteMessages has queued its record.
	select {
	case <-blocked.started:
	case <-time.After(time.Second):
		t.Fatal("SDK produce did not start")
	}
	state := pipeline.Snapshot()
	if state.Outstanding != records || state.ClientFailed != 0 || state.ClientSucceeded != 0 {
		t.Fatalf("blocked SDK ownership state: %+v", state)
	}
	goruntime.GC()
	goruntime.ReadMemStats(&held)
	growth := uint64(0)
	if held.HeapAlloc > before.HeapAlloc {
		growth = held.HeapAlloc - before.HeapAlloc
	}
	// Sixteen one-record batches previously retained about 152MiB of empty
	// Message capacity. Allow ample headroom for records, tasks and test noise,
	// while rejecting that allocation regression without a large test workload.
	if growth > 8<<20 {
		t.Fatalf("16 blocked byte-limited batches retained %d Go heap bytes", growth)
	}
	t.Logf("16 blocked byte-limited batches retained %d Go heap bytes", growth)
}
