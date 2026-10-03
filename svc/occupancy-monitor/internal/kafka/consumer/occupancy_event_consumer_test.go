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
	"schemaVersion": 2,
	"sensorId": "presence-1",
	"type": "PRESENCE",
	"observedAt": "2026-10-03T08:00:00Z",
	"receivedAt": "2026-10-03T08:00:01Z",
	"values": {"present": true}
}`

const validPresenceV2Payload = `{
	"schemaVersion": 2,
	"sensorId": "presence-2",
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
	mu         sync.Mutex
	inserts    []event.Event
	alwaysFail error
	failN      int
	insert     func(context.Context, event.Event) error
}

func (m *mockStore) InsertEvent(ctx context.Context, r event.Event) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.insert != nil {
		return m.insert(ctx, r)
	}
	m.inserts = append(m.inserts, r)
	if m.failN > 0 {
		m.failN--
		return errors.New("db down")
	}
	if m.alwaysFail != nil {
		return m.alwaysFail
	}
	return nil
}

func (m *mockStore) count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.inserts)
}

func (m *mockStore) events() []event.Event {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]event.Event, len(m.inserts))
	copy(out, m.inserts)
	return out
}

func callProcessFetches(
	ctx context.Context,
	client kafkaClient,
	fetches kafkaFetches,
	dbTimeout time.Duration,
	dbRetryDelay time.Duration,
	kafkaRetryDelay time.Duration,
	kafkaMetrics *metrics.Kafka,
	store eventStore,
	readiness *handler.Readiness,
) bool {
	return processFetches(
		ctx,
		client,
		fetches,
		zap.NewNop(),
		dbTimeout,
		dbRetryDelay,
		kafkaRetryDelay,
		kafkaMetrics,
		store,
		readiness,
	)
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
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 7, Value: []byte(validPresencePayload),
		}},
	}

	got := callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, readiness,
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

func TestProcessFetchesPassesV2ToStore(t *testing.T) {
	store := &mockStore{}
	var committed []int64
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			for _, r := range records {
				committed = append(committed, r.Offset)
			}
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload)},
			{Topic: "event-data", Partition: 0, Offset: 2, Value: []byte(validPresenceV2Payload)},
		},
	}

	if !callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
	) {
		t.Fatal("processFetches() = false, want true")
	}

	events := store.events()
	if len(events) != 2 {
		t.Fatalf("inserts = %d, want 2", len(events))
	}
	if events[0].SchemaVersion != 2 || events[0].RoomID != nil ||
		events[0].SensorID == nil || *events[0].SensorID != "presence-1" {
		t.Fatalf("first event = %+v", events[0])
	}
	if events[1].SchemaVersion != 2 || events[1].RoomID != nil ||
		events[1].SensorID == nil || *events[1].SensorID != "presence-2" {
		t.Fatalf("second event = %+v", events[1])
	}
	if len(committed) != 2 || committed[0] != 1 || committed[1] != 2 {
		t.Fatalf("committed offsets = %v, want [1 2]", committed)
	}
}

func TestProcessFetchesSkipsV2WithoutSensorIDAndCommits(t *testing.T) {
	store := &mockStore{}
	var committed int
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			committed += len(records)
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1,
			Value: []byte(`{"schemaVersion":2,"roomId":"kitchen","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`),
		}},
	}

	if !callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
	) {
		t.Fatal("processFetches() = false, want true")
	}
	if store.count() != 0 {
		t.Fatalf("inserts = %d, want 0", store.count())
	}
	if committed != 1 {
		t.Fatalf("committed = %d, want 1", committed)
	}
}

func TestProcessFetchesDoesNotCommitOffsetWhileDatabaseFails(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	attempts := 0
	store := &mockStore{
		insert: func(context.Context, event.Event) error {
			attempts++
			if attempts == 3 {
				cancel()
			}
			return errors.New("db down")
		},
	}

	var committed int
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			committed += len(records)
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresenceV2Payload),
		}},
	}

	got := callProcessFetches(
		ctx, client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
	)

	if got {
		t.Fatal("processFetches() = true, want false once canceled")
	}
	if attempts < 3 {
		t.Fatalf("insert attempts = %d, want at least 3", attempts)
	}
	if committed != 0 {
		t.Fatalf("committed = %d, want 0 while persistence fails", committed)
	}
}

func TestProcessFetchesCommitsOnlyAfterPersist(t *testing.T) {
	var order []string
	store := &mockStore{
		insert: func(context.Context, event.Event) error {
			order = append(order, "persist")
			return nil
		},
	}
	client := &mockKafkaClient{
		commitRecords: func(context.Context, ...*kgo.Record) error {
			order = append(order, "commit")
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresenceV2Payload),
		}},
	}

	if !callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
	) {
		t.Fatal("processFetches() = false, want true")
	}
	if len(order) != 2 || order[0] != "persist" || order[1] != "commit" {
		t.Fatalf("order = %v, want [persist commit]", order)
	}
}

func TestProcessFetchesRetriesSameRecordOnInsertFailure(t *testing.T) {
	store := &mockStore{failN: 1}
	readiness := handler.NewReadiness()
	readiness.SetKafkaReady(true)
	readiness.SetDatabaseReady(true)

	var committed int
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			committed += len(records)
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload)},
			{Topic: "event-data", Partition: 0, Offset: 2, Value: []byte(validPresencePayload)},
		},
	}

	got := callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, readiness,
	)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
	if store.count() != 3 {
		t.Fatalf("insert attempts = %d, want 3", store.count())
	}
	events := store.events()
	if events[0].KafkaOffset != 1 || events[1].KafkaOffset != 1 {
		t.Fatalf("first two attempts offsets = %d,%d, want 1,1", events[0].KafkaOffset, events[1].KafkaOffset)
	}
	if events[2].KafkaOffset != 2 {
		t.Fatalf("third attempt offset = %d, want 2", events[2].KafkaOffset)
	}
	if committed != 2 {
		t.Fatalf("committed = %d, want 2", committed)
	}
	if !readiness.IsReady() {
		t.Fatal("readiness = false, want true after recovery")
	}
}

func TestProcessFetchesDoesNotPollLaterRecordWhileInsertRetrying(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	firstEntered := make(chan struct{})
	blockFirst := make(chan struct{})
	var secondSeen bool

	store := &mockStore{
		insert: func(_ context.Context, r event.Event) error {
			if r.KafkaOffset == 1 {
				select {
				case <-firstEntered:
				default:
					close(firstEntered)
				}
				<-blockFirst
				return nil
			}
			secondSeen = true
			return nil
		},
	}

	client := &mockKafkaClient{}
	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload)},
			{Topic: "event-data", Partition: 0, Offset: 2, Value: []byte(validPresencePayload)},
		},
	}

	done := make(chan bool, 1)
	go func() {
		done <- callProcessFetches(
			ctx, client, fetches,
			time.Second, time.Millisecond, time.Millisecond,
			newTestKafkaMetrics(), store, handler.NewReadiness(),
		)
	}()

	select {
	case <-firstEntered:
	case <-time.After(time.Second):
		t.Fatal("first insert did not start")
	}

	if secondSeen {
		t.Fatal("second record processed before first insert finished")
	}

	close(blockFirst)

	select {
	case got := <-done:
		if !got {
			t.Fatal("processFetches() = false, want true")
		}
	case <-time.After(time.Second):
		t.Fatal("processFetches did not finish")
	}

	if !secondSeen {
		t.Fatal("second record was not processed after first succeeded")
	}
}

func TestProcessFetchesStopsInsertRetryOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	store := &mockStore{alwaysFail: errors.New("db down")}
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
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload),
		}},
	}

	done := make(chan bool, 1)
	go func() {
		done <- callProcessFetches(
			ctx, client, fetches,
			time.Second, time.Hour, time.Millisecond,
			newTestKafkaMetrics(), store, readiness,
		)
	}()

	time.Sleep(20 * time.Millisecond)
	cancel()

	select {
	case got := <-done:
		if got {
			t.Fatal("processFetches() = true, want false on cancel")
		}
	case <-time.After(time.Second):
		t.Fatal("processFetches did not stop on cancel")
	}

	if committed != 0 {
		t.Fatalf("committed = %d, want 0", committed)
	}
	if readiness.IsReady() {
		t.Fatal("readiness = true, want false after DB failure")
	}
}

func TestProcessFetchesRetriesCommitFailure(t *testing.T) {
	store := &mockStore{}
	readiness := handler.NewReadiness()
	readiness.SetReady(true)

	attempts := 0
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			attempts++
			if attempts == 1 {
				return errors.New("commit failed")
			}
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload),
		}},
	}

	got := callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, readiness,
	)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
	if store.count() != 1 {
		t.Fatalf("inserts = %d, want 1", store.count())
	}
	if attempts != 2 {
		t.Fatalf("commit attempts = %d, want 2", attempts)
	}
}

func TestProcessFetchesStopsCommitRetryOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	store := &mockStore{}
	readiness := handler.NewReadiness()
	readiness.SetReady(true)

	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, _ ...*kgo.Record) error {
			return errors.New("commit failed")
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload),
		}},
	}

	done := make(chan bool, 1)
	go func() {
		done <- callProcessFetches(
			ctx, client, fetches,
			time.Second, time.Millisecond, time.Hour,
			newTestKafkaMetrics(), store, readiness,
		)
	}()

	time.Sleep(20 * time.Millisecond)
	cancel()

	select {
	case got := <-done:
		if got {
			t.Fatal("processFetches() = true, want false on cancel")
		}
	case <-time.After(time.Second):
		t.Fatal("processFetches did not stop on commit retry cancel")
	}
}

func TestProcessFetchesRetriesInvalidRecordCommit(t *testing.T) {
	store := &mockStore{}
	readiness := handler.NewReadiness()
	readiness.SetReady(true)

	attempts := 0
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, _ ...*kgo.Record) error {
			attempts++
			if attempts == 1 {
				return errors.New("commit failed")
			}
			return nil
		},
	}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1,
			Value: []byte(`{"schemaVersion":1,"roomId":"kitchen","type":"AIR"}`),
		}},
	}

	got := callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, readiness,
	)

	if !got {
		t.Fatal("processFetches() = false, want true")
	}
	if store.count() != 0 {
		t.Fatalf("inserts = %d, want 0", store.count())
	}
	if attempts != 2 {
		t.Fatalf("commit attempts = %d, want 2", attempts)
	}
}

func TestProcessFetchesSkipsInvalidAndCommits(t *testing.T) {
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
		records: []*kgo.Record{{
			Topic: "event-data", Partition: 0, Offset: 1,
			Value: []byte(`{"schemaVersion":1,"roomId":"kitchen","type":"AIR"}`),
		}},
	}

	got := callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, readiness,
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

func TestProcessFetchesWithoutRecords(t *testing.T) {
	got := callProcessFetches(
		context.Background(), &mockKafkaClient{}, &mockKafkaFetches{},
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), &mockStore{}, handler.NewReadiness(),
	)
	if !got {
		t.Fatal("processFetches() = false, want true")
	}
}

func TestProcessFetchesWithError(t *testing.T) {
	fetches := &mockKafkaFetches{
		errors: []kafkaFetchError{{
			topic: "event-data", partition: 0, err: errors.New("Kafka fetch failed"),
		}},
	}

	got := callProcessFetches(
		context.Background(), &mockKafkaClient{}, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), &mockStore{}, handler.NewReadiness(),
	)
	if got {
		t.Fatal("processFetches() = true, want false")
	}
}

func TestProcessFetchesWithMultipleErrors(t *testing.T) {
	fetches := &mockKafkaFetches{
		errors: []kafkaFetchError{
			{topic: "event-data", partition: 0, err: errors.New("first error")},
			{topic: "event-data", partition: 1, err: errors.New("second error")},
		},
	}

	got := callProcessFetches(
		context.Background(), &mockKafkaClient{}, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), &mockStore{}, handler.NewReadiness(),
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
			ctx, client, zap.NewNop(),
			time.Millisecond, time.Second, time.Millisecond,
			newTestKafkaMetrics(), &mockStore{}, handler.NewReadiness(),
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
					records: []*kgo.Record{{
						Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload),
					}},
				}
			}
			cancel()
			return &mockKafkaFetches{}
		},
	}

	pollEvents(
		ctx, client, zap.NewNop(),
		time.Millisecond, time.Second, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
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
					errors: []kafkaFetchError{{
						topic: "event-data", partition: 0, err: errors.New("Kafka unavailable"),
					}},
				}
			}
			cancel()
			return &mockKafkaFetches{}
		},
	}

	pollEvents(
		ctx, client, zap.NewNop(),
		time.Millisecond, time.Second, time.Millisecond,
		newTestKafkaMetrics(), &mockStore{}, handler.NewReadiness(),
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
				errors: []kafkaFetchError{{
					topic: "event-data", partition: 0, err: errors.New("Kafka unavailable"),
				}},
			}
		},
	}

	done := make(chan struct{})
	go func() {
		pollEvents(
			ctx, client, zap.NewNop(),
			time.Second, time.Second, time.Millisecond,
			newTestKafkaMetrics(), &mockStore{}, handler.NewReadiness(),
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
					errors: []kafkaFetchError{{
						topic: "event-data", partition: 0, err: errors.New("Kafka unavailable"),
					}},
				}
			}
			if pollCount == 2 {
				return &mockKafkaFetches{
					records: []*kgo.Record{{
						Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload),
					}},
				}
			}
			cancel()
			return &mockKafkaFetches{}
		},
	}

	pollEvents(
		ctx, client, zap.NewNop(),
		time.Millisecond, time.Second, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
	)

	if pollCount != 3 {
		t.Fatalf("PollFetches() called %d times, want 3", pollCount)
	}
	if store.count() != 1 {
		t.Fatalf("inserts = %d, want 1", store.count())
	}
}

func TestCheckKafkaReadiness(t *testing.T) {
	readiness := handler.NewReadiness()
	readiness.SetDatabaseReady(true)

	client := &mockKafkaClient{
		ping: func(context.Context) error { return nil },
	}

	checkKafkaReadiness(context.Background(), client, zap.NewNop(), time.Millisecond, readiness)

	if !readiness.IsReady() {
		t.Fatal("readiness = false, want true")
	}
}

func TestCheckKafkaReadinessRetriesAfterError(t *testing.T) {
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

	checkKafkaReadiness(context.Background(), client, zap.NewNop(), time.Millisecond, readiness)

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

func TestProcessFetchesIncrementsMessagesReceivedOncePerRecord(t *testing.T) {
	reg := prometheus.NewRegistry()
	kafkaMetrics := metrics.NewKafka(reg)
	store := &mockStore{failN: 1}
	readiness := handler.NewReadiness()
	client := &mockKafkaClient{}

	fetches := &mockKafkaFetches{
		records: []*kgo.Record{
			{Topic: "event-data", Partition: 0, Offset: 1, Value: []byte(validPresencePayload)},
		},
	}

	if !callProcessFetches(
		context.Background(), client, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		kafkaMetrics, store, readiness,
	) {
		t.Fatal("processFetches() = false, want true")
	}

	if store.count() != 2 {
		t.Fatalf("insert attempts = %d, want 2", store.count())
	}

	expected := `
# HELP kafka_messages_received_total Total Kafka records received by the consumer
# TYPE kafka_messages_received_total counter
kafka_messages_received_total{topic="event-data"} 1
`
	if err := testutil.GatherAndCompare(reg, strings.NewReader(expected), "kafka_messages_received_total"); err != nil {
		t.Fatalf("metrics: %v", err)
	}
}

func TestProcessFetchesIncrementsFetchErrors(t *testing.T) {
	reg := prometheus.NewRegistry()
	kafkaMetrics := metrics.NewKafka(reg)

	fetches := &mockKafkaFetches{
		errors: []kafkaFetchError{
			{topic: "event-data", partition: 0, err: errors.New("first error")},
			{topic: "event-data", partition: 1, err: errors.New("second error")},
		},
	}

	if callProcessFetches(
		context.Background(), &mockKafkaClient{}, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		kafkaMetrics, &mockStore{}, handler.NewReadiness(),
	) {
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

func TestWaitRetryCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if waitRetry(ctx, time.Second) {
		t.Fatal("waitRetry() = true, want false")
	}
}
