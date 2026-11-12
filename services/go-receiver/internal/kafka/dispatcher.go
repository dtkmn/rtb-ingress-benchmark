package kafka

import (
	"context"
	"net"
	"sync"

	kgo "github.com/segmentio/kafka-go"
)

// cancelTransport cancels actual in-flight transport calls on teardown; HTTP
// request cancellation never reaches it. Future writer retries also fail fast.
type cancelTransport struct {
	inner *kgo.Transport
	ctx   context.Context
}

func (t *cancelTransport) RoundTrip(ctx context.Context, addr net.Addr, req kgo.Request) (kgo.Response, error) {
	call, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(t.ctx, cancel)
	defer stop()
	if err := t.ctx.Err(); err != nil {
		return nil, err
	}
	return t.inner.RoundTrip(call, addr, req)
}

type kafkaDispatcher struct {
	writer interface {
		MessageWriter
		Close() error
	}
	cancel    context.CancelFunc
	transport *kgo.Transport
	closeOnce sync.Once
	closed    chan struct{}
	closeErr  error
}

func completeWriterMessages(messages []kgo.Message, err error) {
	for _, message := range messages {
		if complete, ok := message.WriterData.(func(error)); ok {
			complete(err)
		}
	}
}

func (d *kafkaDispatcher) Dispatch(ctx context.Context, record Record, complete func(error)) {
	err := d.writer.WriteMessages(ctx, kgo.Message{Topic: record.Topic, Key: record.Key, Value: record.Value, WriterData: complete})
	if err != nil {
		complete(err)
	}
	// Async nil means queued, not delivered. Only Completion settles success.
}
func (d *kafkaDispatcher) Close(ctx context.Context) error {
	d.closeOnce.Do(func() {
		d.cancel()
		go func() { d.closeErr = d.writer.Close(); d.transport.CloseIdleConnections(); close(d.closed) }()
	})
	select {
	case <-d.closed:
		return d.closeErr
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Synchronous test writers preserve their terminal-outcome contract. Production
// always uses kafkaDispatcher with kafka-go's actual Completion callback.
type synchronousDispatcher struct{ writer MessageWriter }

func (d *synchronousDispatcher) Dispatch(ctx context.Context, record Record, complete func(error)) {
	complete(d.writer.WriteMessages(ctx, kgo.Message{Topic: record.Topic, Key: record.Key, Value: record.Value}))
}
func (d *synchronousDispatcher) Close(context.Context) error { return nil }
