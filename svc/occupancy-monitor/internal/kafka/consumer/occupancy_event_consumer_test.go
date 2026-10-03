package consumer

import (
	"context"
	"errors"
	"occupancy-monitor/internal/event"
	"occupancy-monitor/internal/handler"
	"occupancy-monitor/internal/metrics"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
)

const validPresencePayload = `{
	"schemaVersion": 1,
	"roomId": "kitchen",
	"deviceId": "presence-1",
	"type": "PRESENCE",
	"observedAt": "2026-10-03T08:00:00Z",
	"receivedAt": "2026-10-03T08:00:01Z",
	"values": {"present": true}
}`

func newTestKafkaMetrics() *metrics.Kafka {
	return metrics.NewKafka(prometheus.NewRegistry())
}

type mockKafkaClient struct {
	ping          func(context.Context) error
	pollFetches   func(context.Context) kafkaFetches
	commitRecords func(context.Context, ...*kgo.Record) error
}

func (m *mockKafkaClient) Ping(ctx context.Context) error {
	return m.ping(ctx)
}

func (m *mockKafkaClient) PollFetches(ctx context.Context) kafkaFetches {
	return m.pollFetches(ctx)
}

func (m *mockKafkaClient) CommitRecords(ctx context.Context, records ...*kgo.Record) error {
	if m.commitRecords == nil {
		return nil
	}
	return m.commitRecords(ctx, records...)
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

type mockStore struct {
	mu      sync.Mutex
	inserts []event.Event
	err     error
}

func (m *mockStore) InsertEvent(_ context.Context, r event.Event) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.err != nil {
		return m.err
	}
	m.inserts = append(m.inserts, r)
	return nil
}

func (m *mockStore) count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.inserts)
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

func TestProcessFetchesPersistsAndCommits(t *testing.T) {
	logger := zap.NewNop()
	store := &mockStore{}
	readiness := handler.NewReadiness()
	readiness.SetReady(true)

	var committed int
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			committed += len(records)
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{
				Topic:     "event-data",
				Partition: 0,
				Offset:    7,
				Value:     []byte(validPresencePayload),
			},
		},
	}

	got := processFetches(
		context.Background(),
		client,
		fetches,
		logger,
		time.Second,
		newTestKafkaMetrics(),
		store,
		readiness,
	)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
	if store.count() != 1 {
		t.Fatalf("inserts = %d, want 1", store.count())
	}
	if committed != 1 {
		t.Fatalf("committed = %d, want 1", committed)
	}
	if !readiness.IsReady() {
		t.Fatal("readiness = false, want true")
	}
}

func TestProcessFetchesSkipsInvalidAndCommits(t *testing.T) {
	logger := zap.NewNop()
	store := &mockStore{}
	readiness := handler.NewReadiness()
	readiness.SetReady(true)

	var committed int
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			committed += len(records)
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{
				Topic:     "event-data",
				Partition: 0,
				Offset:    1,
				Value:     []byte(`{"roomId":"kitchen","type":"AIR"}`),
			},
		},
	}

	got := processFetches(
		context.Background(),
		client,
		fetches,
		logger,
		time.Second,
		newTestKafkaMetrics(),
		store,
		readiness,
	)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
	if store.count() != 0 {
		t.Fatalf("inserts = %d, want 0", store.count())
	}
	if committed != 1 {
		t.Fatalf("committed = %d, want 1", committed)
	}
}

func TestProcessFetchesDoesNotCommitOnPersistError(t *testing.T) {
	logger := zap.NewNop()
	store := &mockStore{err: errors.New("db down")}
	readiness := handler.NewReadiness()
	readiness.SetReady(true)

	var committed int
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			committed += len(records)
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{
				Topic:     "event-data",
				Partition: 0,
				Offset:    1,
				Value:     []byte(validPresencePayload),
			},
		},
	}

	got := processFetches(
		context.Background(),
		client,
		fetches,
		logger,
		time.Second,
		newTestKafkaMetrics(),
		store,
		readiness,
	)

	if got {
		t.Fatal("processFetches() = true, want false")
	}
	if committed != 0 {
		t.Fatalf("committed = %d, want 0", committed)
	}
	if readiness.IsReady() {
		t.Fatal("readiness = true, want false after DB failure")
	}
}

func TestProcessFetchesWithoutRecords(t *testing.T) {
	logger := zap.NewNop()
	client := &mockKafkaClient{}
	store := &mockStore{}
	readiness := handler.NewReadiness()

	got := processFetches(
		context.Background(),
		client,
		&mockKafkaFetches{},
		logger,
		time.Second,
		newTestKafkaMetrics(),
		store,
		readiness,
	)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
}

func TestProcessFetchesWithError(t *testing.T) {
	logger := zap.NewNop()
	client := &mockKafkaClient{}
	store := &mockStore{}
	readiness := handler.NewReadiness()

	fetches := &mockKafkaFetches{
		errors: []kafkaFetchError{
			{
				topic:     "event-data",
				partition: 0,
				err:       errors.New("Kafka fetch failed"),
			},
		},
	}

	got := processFetches(
		context.Background(),
		client,
		fetches,
		logger,
		time.Second,
		newTestKafkaMetrics(),
		store,
		readiness,
	)

	if got {
		t.Fatal("processFetches() = true, want false")
	}
}

func TestProcessFetchesWithMultipleErrors(t *testing.T) {
	logger := zap.NewNop()
	client := &mockKafkaClient{}
	store := &mockStore{}
	readiness := handler.NewReadiness()

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

	got := processFetches(
		context.Background(),
		client,
		fetches,
		logger,
		time.Second,
		newTestKafkaMetrics(),
		store,
		readiness,
	)

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
		pollEvents(
			ctx,
			client,
			zap.NewNop(),
			time.Millisecond,
			time.Second,
			newTestKafkaMetrics(),
			&mockStore{},
			handler.NewReadiness(),
		)
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
	store := &mockStore{}

	client := &mockKafkaClient{
		pollFetches: func(context.Context) kafkaFetches {
			pollCount++

			if pollCount == 1 {
				return &mockKafkaFetches{
					records: []*kgo.Record{
						{
							Topic:     "event-data",
							Partition: 0,
							Offset:    1,
							Value:     []byte(validPresencePayload),
						},
					},
				}
			}

			cancel()

			return &mockKafkaFetches{}
		},
	}

	pollEvents(
		ctx,
		client,
		zap.NewNop(),
		time.Millisecond,
		time.Second,
		newTestKafkaMetrics(),
		store,
		handler.NewReadiness(),
	)

	if pollCount != 2 {
		t.Fatalf("PollFetches() called %d times, want 2", pollCount)
	}
	if store.count() != 1 {
		t.Fatalf("inserts = %d, want 1", store.count())
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

	pollEvents(
		ctx,
		client,
		zap.NewNop(),
		time.Millisecond,
		time.Second,
		newTestKafkaMetrics(),
		&mockStore{},
		handler.NewReadiness(),
	)

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
		pollEvents(
			ctx,
			client,
			zap.NewNop(),
			time.Second,
			time.Second,
			newTestKafkaMetrics(),
			&mockStore{},
			handler.NewReadiness(),
		)
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
	store := &mockStore{}

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
							Offset:    1,
							Value:     []byte(validPresencePayload),
						},
					},
				}
			}

			cancel()

			return &mockKafkaFetches{}
		},
	}

	pollEvents(
		ctx,
		client,
		zap.NewNop(),
		time.Millisecond,
		time.Second,
		newTestKafkaMetrics(),
		store,
		handler.NewReadiness(),
	)

	if pollCount != 3 {
		t.Fatalf("PollFetches() called %d times, want 3", pollCount)
	}
	if store.count() != 1 {
		t.Fatalf("inserts = %d, want 1", store.count())
	}
}

func TestCheckKafkaReadiness(t *testing.T) {
	ctx := context.Background()
	readiness := handler.NewReadiness()
	readiness.SetDatabaseReady(true)

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
	readiness.SetDatabaseReady(true)
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
	readiness.SetDatabaseReady(true)

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

func TestProcessFetchesIncrementsMessagesReceived(t *testing.T) {
	reg := prometheus.NewRegistry()
	kafkaMetrics := metrics.NewKafka(reg)
	logger := zap.NewNop()
	store := &mockStore{}
	readiness := handler.NewReadiness()
	client := &mockKafkaClient{}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload)},
			{Topic: "event-data", Partition: 0, Offset: 2, Value: []byte(validPresencePayload)},
		},
	}

	if !processFetches(context.Background(), client, fetches, logger, time.Second, kafkaMetrics, store, readiness) {
		t.Fatal("processFetches() = false, want true")
	}

	expected := `
# HELP kafka_messages_received_total Total Kafka records received by the consumer
# TYPE kafka_messages_received_total counter
kafka_messages_received_total{topic="event-data"} 2
`
	if err := testutil.GatherAndCompare(reg, strings.NewReader(expected), "kafka_messages_received_total"); err != nil {
		t.Fatalf("metrics: %v", err)
	}
}

func TestProcessFetchesIncrementsFetchErrors(t *testing.T) {
	reg := prometheus.NewRegistry()
	kafkaMetrics := metrics.NewKafka(reg)
	logger := zap.NewNop()
	client := &mockKafkaClient{}
	store := &mockStore{}
	readiness := handler.NewReadiness()

	fetches := &mockKafkaFetches{
		errors: []kafkaFetchError{
			{topic: "event-data", partition: 0, err: errors.New("first error")},
			{topic: "event-data", partition: 1, err: errors.New("second error")},
		},
	}

	if processFetches(context.Background(), client, fetches, logger, time.Second, kafkaMetrics, store, readiness) {
		t.Fatal("processFetches() = true, want false")
	}

	expected := `
# HELP kafka_fetch_errors_total Total Kafka fetch errors observed by the consumer
# TYPE kafka_fetch_errors_total counter
kafka_fetch_errors_total{topic="event-data"} 2
`
	if err := testutil.GatherAndCompare(reg, strings.NewReader(expected), "kafka_fetch_errors_total"); err != nil {
		t.Fatalf("metrics: %v", err)
	}
}
