package consumer

import (
	"context"
	"errors"
	"occupancy-monitor/internal/handler"
	"testing"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
)

type mockKafkaClient struct {
	ping        func(context.Context) error
	pollFetches func(context.Context) kafkaFetches
}

func (m *mockKafkaClient) Ping(ctx context.Context) error {
	return m.ping(ctx)
}

func (m *mockKafkaClient) PollFetches(ctx context.Context) kafkaFetches {
	return m.pollFetches(ctx)
}

type mockKafkaFetches struct {
	errors  []kafkaFetchError
	records []*kgo.Record
}

type kafkaFetchError struct {
	topic     string
	partition int32
	err       error
}

func (m *mockKafkaFetches) EachError(fn func(string, int32, error)) {
	for _, fetchError := range m.errors {
		fn(fetchError.topic, fetchError.partition, fetchError.err)
	}
}

func (m *mockKafkaFetches) EachRecord(fn func(*kgo.Record)) {
	for _, record := range m.records {
		fn(record)
	}
}

func TestNewKafkaConsumer(t *testing.T) {
	logger := zap.NewNop()

	client, err := NewKafkaConsumer(
		[]string{"localhost:9092"},
		"event-data",
		"occupancy-monitor",
		logger,
	)

	if err != nil {
		t.Fatalf("NewKafkaConsumer() error = %v", err)
	}

	if client == nil {
		t.Fatal("NewKafkaConsumer() returned nil")
	}

	client.Close()
}

func TestProcessFetchesWithRecord(t *testing.T) {
	logger := zap.NewNop()

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{
				Topic:     "event-data",
				Partition: 0,
				Value:     []byte(`{"occupancy":true}`),
			},
		},
	}

	got := processFetches(fetches, logger)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
}

func TestProcessFetchesWithoutRecords(t *testing.T) {
	logger := zap.NewNop()

	fetches := &mockKafkaFetches{}

	got := processFetches(fetches, logger)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
}

func TestProcessFetchesWithError(t *testing.T) {
	logger := zap.NewNop()

	fetches := &mockKafkaFetches{
		errors: []kafkaFetchError{
			{
				topic:     "event-data",
				partition: 0,
				err:       errors.New("Kafka fetch failed"),
			},
		},
	}

	got := processFetches(fetches, logger)

	if got {
		t.Fatal("processFetches() = true, want false")
	}
}

func TestProcessFetchesWithMultipleErrors(t *testing.T) {
	logger := zap.NewNop()

	fetches := &mockKafkaFetches{
		errors: []kafkaFetchError{
			{
				topic:     "event-data",
				partition: 0,
				err:       errors.New("first error"),
			},
			{
				topic:     "event-data",
				partition: 1,
				err:       errors.New("second error"),
			},
		},
	}

	got := processFetches(fetches, logger)

	if got {
		t.Fatal("processFetches() = true, want false")
	}
}

func TestPollEventsStopsWhenContextIsCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	client := &mockKafkaClient{
		pollFetches: func(context.Context) kafkaFetches {
			return &mockKafkaFetches{}
		},
	}

	done := make(chan struct{})

	go func() {
		pollEvents(ctx, client, zap.NewNop(), time.Millisecond)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("pollEvents() did not stop")
	}
}

func TestPollEventsProcessesRecords(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pollCount := 0

	client := &mockKafkaClient{
		pollFetches: func(context.Context) kafkaFetches {
			pollCount++

			if pollCount == 1 {
				return &mockKafkaFetches{
					records: []*kgo.Record{
						{
							Topic:     "event-data",
							Partition: 0,
							Value:     []byte(`{"occupancy":true}`),
						},
					},
				}
			}

			cancel()

			return &mockKafkaFetches{}
		},
	}

	pollEvents(ctx, client, zap.NewNop(), time.Millisecond)

	if pollCount != 2 {
		t.Fatalf("PollFetches() called %d times, want 2", pollCount)
	}
}

func TestPollEventsRetriesAfterFetchError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pollCount := 0

	client := &mockKafkaClient{
		pollFetches: func(context.Context) kafkaFetches {
			pollCount++

			if pollCount == 1 {
				return &mockKafkaFetches{
					errors: []kafkaFetchError{
						{
							topic:     "event-data",
							partition: 0,
							err:       errors.New("Kafka unavailable"),
						},
					},
				}
			}

			cancel()

			return &mockKafkaFetches{}
		},
	}

	pollEvents(ctx, client, zap.NewNop(), time.Millisecond)

	if pollCount != 2 {
		t.Fatalf("PollFetches() called %d times, want 2", pollCount)
	}
}

func TestPollEventsStopsDuringRetryDelay(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())

	client := &mockKafkaClient{
		pollFetches: func(context.Context) kafkaFetches {
			return &mockKafkaFetches{
				errors: []kafkaFetchError{
					{
						topic:     "event-data",
						partition: 0,
						err:       errors.New("Kafka unavailable"),
					},
				},
			}
		},
	}

	done := make(chan struct{})

	go func() {
		pollEvents(ctx, client, zap.NewNop(), time.Second)
		close(done)
	}()

	time.Sleep(10 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("pollEvents() did not stop during retry delay")
	}
}

func TestPollEventsHandlesRecordAfterRetry(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pollCount := 0

	client := &mockKafkaClient{
		pollFetches: func(context.Context) kafkaFetches {
			pollCount++

			if pollCount == 1 {
				return &mockKafkaFetches{
					errors: []kafkaFetchError{
						{
							topic:     "event-data",
							partition: 0,
							err:       errors.New("Kafka unavailable"),
						},
					},
				}
			}

			if pollCount == 2 {
				return &mockKafkaFetches{
					records: []*kgo.Record{
						{
							Topic:     "event-data",
							Partition: 0,
							Value:     []byte(`{"occupancy":true}`),
						},
					},
				}
			}

			cancel()

			return &mockKafkaFetches{}
		},
	}

	pollEvents(ctx, client, zap.NewNop(), time.Millisecond)

	if pollCount != 3 {
		t.Fatalf("PollFetches() called %d times, want 3", pollCount)
	}
}

func TestCheckKafkaReadiness(t *testing.T) {
	ctx := context.Background()
	readiness := handler.NewReadiness()

	client := &mockKafkaClient{
		ping: func(context.Context) error {
			return nil
		},
	}

	checkKafkaReadiness(ctx, client, zap.NewNop(), time.Millisecond, readiness)

	if !readiness.IsReady() {
		t.Fatal("readiness = false, want true")
	}
}

func TestCheckKafkaReadinessRetriesAfterError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	readiness := handler.NewReadiness()
	pingCount := 0

	client := &mockKafkaClient{
		ping: func(context.Context) error {
			pingCount++

			if pingCount == 1 {
				return errors.New("Kafka unavailable")
			}

			return nil
		},
	}

	checkKafkaReadiness(ctx, client, zap.NewNop(), time.Millisecond, readiness)

	if pingCount != 2 {
		t.Fatalf("Ping() called %d times, want 2", pingCount)
	}

	if !readiness.IsReady() {
		t.Fatal("readiness = false, want true")
	}
}

func TestCheckKafkaReadinessStopsWhenContextIsCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())

	readiness := handler.NewReadiness()

	client := &mockKafkaClient{
		ping: func(context.Context) error {
			return errors.New("Kafka unavailable")
		},
	}

	done := make(chan struct{})

	go func() {
		checkKafkaReadiness(ctx, client, zap.NewNop(), time.Second, readiness)
		close(done)
	}()

	time.Sleep(10 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("checkKafkaReadiness() did not stop")
	}

	if readiness.IsReady() {
		t.Fatal("readiness = true, want false")
	}
}
