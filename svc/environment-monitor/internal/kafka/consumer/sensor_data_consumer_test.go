package consumer

import (
	"context"
	"environment-monitor/internal/handler"
	"environment-monitor/internal/metrics"
	"environment-monitor/internal/reading"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
)

const validAIRPayload = `{
	"schemaVersion": 2,
	"sensorId": "sensor-1",
	"type": "AIR",
	"observedAt": "2026-10-03T08:00:00Z",
	"receivedAt": "2026-10-03T08:00:01Z",
	"values": {"temperature": 22.5, "humidity": 45, "co2": 700}
}`

const validV2AIRPayload = `{
	"schemaVersion": 2,
	"sensorId": "sensor-2",
	"type": "AIR",
	"observedAt": "2026-10-03T08:00:00Z",
	"receivedAt": "2026-10-03T08:00:01Z",
	"values": {"temperature": 22.5, "humidity": 45, "co2": 700}
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
	inserts    []reading.Reading
	alwaysFail error
	failN      int
	insert     func(context.Context, reading.Reading) error
}

func (m *mockStore) InsertReading(ctx context.Context, r reading.Reading) error {
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

func (m *mockStore) readings() []reading.Reading {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]reading.Reading, len(m.inserts))
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
	store readingStore,
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
		"sensor-data",
		"environment-monitor",
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
			Topic: "sensor-data", Partition: 0, Offset: 7, Value: []byte(validAIRPayload),
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
			{Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload)},
			{Topic: "sensor-data", Partition: 0, Offset: 2, Value: []byte(validAIRPayload)},
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
	readings := store.readings()
	if readings[0].KafkaOffset != 1 || readings[1].KafkaOffset != 1 {
		t.Fatalf("first two attempts offsets = %d,%d, want 1,1", readings[0].KafkaOffset, readings[1].KafkaOffset)
	}
	if readings[2].KafkaOffset != 2 {
		t.Fatalf("third attempt offset = %d, want 2", readings[2].KafkaOffset)
	}
	if committed != 2 {
		t.Fatalf("committed = %d, want 2", committed)
	}
	if !readiness.IsReady() {
		t.Fatal("readiness = false, want true after recovery")
	}
}

func processWithPayloads(t *testing.T, store readingStore, client kafkaClient, payloads ...string) bool {
	t.Helper()

	records := make([]*kgo.Record, len(payloads))
	for i, payload := range payloads {
		records[i] = &kgo.Record{Topic: "sensor-data", Partition: 0, Offset: int64(i + 1), Value: []byte(payload)}
	}

	return callProcessFetches(
		context.Background(), client, &mockKafkaFetches{records: records},
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
	)
}

func TestProcessFetchesPassesV2ToStore(t *testing.T) {
	store := &mockStore{}

	if !processWithPayloads(t, store, &mockKafkaClient{}, validAIRPayload, validV2AIRPayload) {
		t.Fatal("processFetches() = false, want true")
	}

	got := store.readings()
	if len(got) != 2 {
		t.Fatalf("inserts = %d, want 2", len(got))
	}

	first := got[0]
	if first.SensorID == nil || *first.SensorID != "sensor-1" || first.RoomID != nil {
		t.Fatalf("first sensor/room = %v/%v, want sensor-1/nil", first.SensorID, first.RoomID)
	}

	second := got[1]
	if second.SensorID == nil || *second.SensorID != "sensor-2" || second.RoomID != nil {
		t.Fatalf("second sensor/room = %v/%v, want sensor-2/nil", second.SensorID, second.RoomID)
	}
}

func TestProcessFetchesSkipsAndCommitsV2WithLegacyIdentityFields(t *testing.T) {
	store := &mockStore{}
	var committed int
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			committed += len(records)
			return nil
		},
	}

	payload := `{"schemaVersion":2,"sensorId":"s","roomId":"kitchen","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`
	if !processWithPayloads(t, store, client, payload) {
		t.Fatal("processFetches() = false, want true")
	}
	if store.count() != 0 {
		t.Fatalf("inserts = %d, want 0", store.count())
	}
	if committed != 1 {
		t.Fatalf("committed = %d, want 1", committed)
	}
}

func TestProcessFetchesCommitsOnlyAfterSuccessfulPersist(t *testing.T) {
	var mu sync.Mutex
	var events []string
	failures := 2

	store := &mockStore{insert: func(_ context.Context, r reading.Reading) error {
		mu.Lock()
		defer mu.Unlock()
		if failures > 0 {
			failures--
			events = append(events, "insert-fail")
			return errors.New("db down")
		}
		events = append(events, "insert-ok")
		return nil
	}}
	client := &mockKafkaClient{
		commitRecords: func(_ context.Context, records ...*kgo.Record) error {
			mu.Lock()
			defer mu.Unlock()
			events = append(events, "commit")
			return nil
		},
	}

	if !processWithPayloads(t, store, client, validV2AIRPayload) {
		t.Fatal("processFetches() = false, want true")
	}

	want := []string{"insert-fail", "insert-fail", "insert-ok", "commit"}
	if strings.Join(events, ",") != strings.Join(want, ",") {
		t.Fatalf("events = %v, want %v", events, want)
	}
}

func TestProcessFetchesRedeliveredOffsetIsIdempotent(t *testing.T) {
	stored := map[int64]reading.Reading{}
	store := &mockStore{insert: func(_ context.Context, r reading.Reading) error {
		if _, ok := stored[r.KafkaOffset]; !ok {
			stored[r.KafkaOffset] = r
		}
		return nil
	}}

	fetches := &mockKafkaFetches{records: []*kgo.Record{
		{Topic: "sensor-data", Partition: 0, Offset: 5, Value: []byte(validV2AIRPayload)},
		{Topic: "sensor-data", Partition: 0, Offset: 5, Value: []byte(validV2AIRPayload)},
	}}

	got := callProcessFetches(
		context.Background(), &mockKafkaClient{}, fetches,
		time.Second, time.Millisecond, time.Millisecond,
		newTestKafkaMetrics(), store, handler.NewReadiness(),
	)
	if !got {
		t.Fatal("processFetches() = false, want true")
	}
	if len(stored) != 1 {
		t.Fatalf("stored = %d, want 1", len(stored))
	}
}

func TestProcessFetchesDoesNotPollLaterRecordWhileInsertRetrying(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	firstEntered := make(chan struct{})
	blockFirst := make(chan struct{})
	var secondSeen bool

	store := &mockStore{
		insert: func(_ context.Context, r reading.Reading) error {
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
			{Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload)},
			{Topic: "sensor-data", Partition: 0, Offset: 2, Value: []byte(validAIRPayload)},
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
			Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload),
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
			Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload),
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
			Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload),
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
			Topic: "sensor-data", Partition: 0, Offset: 1,
			Value: []byte(`{"roomId":"kitchen","type":"PRESENCE"}`),
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
			Topic: "sensor-data", Partition: 0, Offset: 1,
			Value: []byte(`{"roomId":"kitchen","type":"PRESENCE"}`),
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
			topic: "sensor-data", partition: 0, err: errors.New("Kafka fetch failed"),
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
			{topic: "sensor-data", partition: 0, err: errors.New("first error")},
			{topic: "sensor-data", partition: 1, err: errors.New("second error")},
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
						Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload),
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
						topic: "sensor-data", partition: 0, err: errors.New("Kafka unavailable"),
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
					topic: "sensor-data", partition: 0, err: errors.New("Kafka unavailable"),
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
						topic: "sensor-data", partition: 0, err: errors.New("Kafka unavailable"),
					}},
				}
			}
			if pollCount == 2 {
				return &mockKafkaFetches{
					records: []*kgo.Record{{
						Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload),
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
			{Topic: "sensor-data", Partition: 0, Offset: 1, Value: []byte(validAIRPayload)},
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
kafka_messages_received_total{topic="sensor-data"} 1
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
			{topic: "sensor-data", partition: 0, err: errors.New("first error")},
			{topic: "sensor-data", partition: 1, err: errors.New("second error")},
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
kafka_fetch_errors_total{topic="sensor-data"} 2
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
