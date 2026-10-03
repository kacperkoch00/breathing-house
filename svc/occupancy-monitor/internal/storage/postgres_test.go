package storage

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"occupancy-monitor/internal/event"
)

type fakeSensor struct {
	displayName string
	roomID      *string
}

type fakeEventKey struct {
	topic     string
	partition int32
	offset    int64
}

// fakeDB models the committed state of home_api.sensor and
// occupancy.occupancy_event with transactional commit/rollback and the
// unique (topic, partition, offset) constraint.
type fakeDB struct {
	sensors  map[string]fakeSensor
	events   []eventRow
	seen     map[fakeEventKey]bool
	failStep string
	commits  int
	rollback int
}

func newFakeDB() *fakeDB {
	return &fakeDB{
		sensors: map[string]fakeSensor{},
		seen:    map[fakeEventKey]bool{},
	}
}

func (d *fakeDB) Begin(context.Context) (eventTx, error) {
	if d.failStep == "begin" {
		return nil, errors.New("begin failed")
	}

	return &fakeTx{db: d, sensors: map[string]fakeSensor{}}, nil
}

func (d *fakeDB) setRoom(sensorID string, roomID *string) {
	sensor := d.sensors[sensorID]
	sensor.roomID = roomID
	d.sensors[sensorID] = sensor
}

type fakeTx struct {
	db      *fakeDB
	sensors map[string]fakeSensor
	events  []eventRow
	done    bool
}

func (t *fakeTx) lookup(sensorID string) (fakeSensor, bool) {
	if sensor, ok := t.sensors[sensorID]; ok {
		return sensor, true
	}
	sensor, ok := t.db.sensors[sensorID]
	return sensor, ok
}

func (t *fakeTx) UpsertSensor(_ context.Context, sensorID string) error {
	if t.db.failStep == "upsert" {
		return errors.New("upsert failed")
	}
	if _, ok := t.lookup(sensorID); ok {
		return nil
	}
	displayName := sensorID
	if len(displayName) > 100 {
		displayName = displayName[:100]
	}
	t.sensors[sensorID] = fakeSensor{displayName: displayName}
	return nil
}

func (t *fakeTx) SensorRoomID(_ context.Context, sensorID string) (*string, error) {
	if t.db.failStep == "read-room" {
		return nil, errors.New("read room failed")
	}
	sensor, ok := t.lookup(sensorID)
	if !ok {
		return nil, nil
	}
	return sensor.roomID, nil
}

func (t *fakeTx) InsertEvent(_ context.Context, row eventRow) error {
	if t.db.failStep == "insert" {
		return errors.New("insert failed")
	}
	t.events = append(t.events, row)
	return nil
}

func (t *fakeTx) Commit(context.Context) error {
	if t.db.failStep == "commit" {
		return errors.New("commit failed")
	}
	t.done = true
	t.db.commits++
	for id, sensor := range t.sensors {
		t.db.sensors[id] = sensor
	}
	for _, row := range t.events {
		key := fakeEventKey{row.KafkaTopic, row.KafkaPartition, row.KafkaOffset}
		if t.db.seen[key] {
			continue
		}
		t.db.seen[key] = true
		t.db.events = append(t.db.events, row)
	}
	return nil
}

func (t *fakeTx) Rollback(context.Context) error {
	if !t.done {
		t.db.rollback++
	}
	t.done = true
	return nil
}

func str(value string) *string { return &value }

func v2Event(sensorID string, offset int64) event.Event {
	present := true
	return event.Event{
		SchemaVersion:  event.SchemaVersionV2,
		SensorID:       str(sensorID),
		EventType:      event.EventTypePresence,
		Present:        &present,
		ObservedAt:     time.Date(2026, 10, 3, 8, 0, 0, 0, time.UTC),
		ReceivedAt:     time.Date(2026, 10, 3, 8, 0, 1, 0, time.UTC),
		KafkaTopic:     "event-data",
		KafkaPartition: 0,
		KafkaOffset:    offset,
	}
}

func requireStringPtr(t *testing.T, field string, got *string, want *string) {
	t.Helper()
	switch {
	case got == nil && want == nil:
	case got == nil || want == nil || *got != *want:
		t.Fatalf("%s = %v, want %v", field, deref(got), deref(want))
	}
}

func deref(value *string) string {
	if value == nil {
		return "<nil>"
	}
	return *value
}

func TestPersistEventV2RegistersUnknownSensor(t *testing.T) {
	db := newFakeDB()

	if err := persistEvent(context.Background(), db, v2Event("presence-1", 1)); err != nil {
		t.Fatalf("persistEvent() error = %v", err)
	}

	sensor, ok := db.sensors["presence-1"]
	if !ok {
		t.Fatal("sensor presence-1 was not registered")
	}
	if sensor.displayName != "presence-1" {
		t.Fatalf("display_name = %q, want presence-1", sensor.displayName)
	}
	if sensor.roomID != nil {
		t.Fatalf("sensor room = %q, want nil", *sensor.roomID)
	}
	if len(db.events) != 1 {
		t.Fatalf("events = %d, want 1", len(db.events))
	}

	row := db.events[0]
	requireStringPtr(t, "sensor_id", row.SensorID, str("presence-1"))
	requireStringPtr(t, "room_id", row.RoomID, nil)
}

func TestPersistEventPreservesExistingDisplayName(t *testing.T) {
	db := newFakeDB()
	db.sensors["presence-1"] = fakeSensor{displayName: "Kitchen motion", roomID: str("kitchen")}

	if err := persistEvent(context.Background(), db, v2Event("presence-1", 1)); err != nil {
		t.Fatalf("persistEvent() error = %v", err)
	}

	if got := db.sensors["presence-1"].displayName; got != "Kitchen motion" {
		t.Fatalf("display_name = %q, want Kitchen motion", got)
	}
}

func TestPersistEventSnapshotsCurrentSensorRoom(t *testing.T) {
	db := newFakeDB()
	db.sensors["presence-1"] = fakeSensor{displayName: "presence-1", roomID: str("kitchen")}

	if err := persistEvent(context.Background(), db, v2Event("presence-1", 1)); err != nil {
		t.Fatalf("persistEvent() error = %v", err)
	}

	requireStringPtr(t, "room_id", db.events[0].RoomID, str("kitchen"))
}

func TestPersistEventSnapshotsNullRoomForUnassignedSensor(t *testing.T) {
	db := newFakeDB()
	db.sensors["presence-1"] = fakeSensor{displayName: "presence-1"}

	if err := persistEvent(context.Background(), db, v2Event("presence-1", 1)); err != nil {
		t.Fatalf("persistEvent() error = %v", err)
	}

	requireStringPtr(t, "room_id", db.events[0].RoomID, nil)
}

func TestPersistEventSensorMoveAffectsOnlyLaterEvents(t *testing.T) {
	db := newFakeDB()
	db.sensors["presence-1"] = fakeSensor{displayName: "presence-1", roomID: str("kitchen")}

	if err := persistEvent(context.Background(), db, v2Event("presence-1", 1)); err != nil {
		t.Fatalf("persistEvent() before move error = %v", err)
	}

	db.setRoom("presence-1", str("hallway"))

	if err := persistEvent(context.Background(), db, v2Event("presence-1", 2)); err != nil {
		t.Fatalf("persistEvent() after move error = %v", err)
	}

	db.setRoom("presence-1", nil)

	if err := persistEvent(context.Background(), db, v2Event("presence-1", 3)); err != nil {
		t.Fatalf("persistEvent() after unassign error = %v", err)
	}

	if len(db.events) != 3 {
		t.Fatalf("events = %d, want 3", len(db.events))
	}
	requireStringPtr(t, "event 1 room_id", db.events[0].RoomID, str("kitchen"))
	requireStringPtr(t, "event 2 room_id", db.events[1].RoomID, str("hallway"))
	requireStringPtr(t, "event 3 room_id", db.events[2].RoomID, nil)
}

func TestPersistEventRequiresSensorID(t *testing.T) {
	db := newFakeDB()
	e := v2Event("presence-1", 1)
	e.SensorID = nil

	if err := persistEvent(context.Background(), db, e); err == nil {
		t.Fatal("persistEvent() error = nil, want error")
	}
	if len(db.sensors) != 0 || len(db.events) != 0 {
		t.Fatalf("committed sensors=%d events=%d, want none", len(db.sensors), len(db.events))
	}
}

func TestPersistEventFailureLeavesNoPartialState(t *testing.T) {
	for _, step := range []string{"begin", "upsert", "read-room", "insert", "commit"} {
		t.Run(step, func(t *testing.T) {
			db := newFakeDB()
			db.failStep = step

			if err := persistEvent(context.Background(), db, v2Event("presence-1", 1)); err == nil {
				t.Fatal("persistEvent() error = nil, want error")
			}

			if len(db.sensors) != 0 || len(db.events) != 0 {
				t.Fatalf("committed sensors=%d events=%d, want none", len(db.sensors), len(db.events))
			}
			if step != "begin" && db.rollback != 1 {
				t.Fatalf("rollbacks = %d, want 1", db.rollback)
			}

			db.failStep = ""
			if err := persistEvent(context.Background(), db, v2Event("presence-1", 1)); err != nil {
				t.Fatalf("retry error = %v", err)
			}
			if len(db.events) != 1 {
				t.Fatalf("events after retry = %d, want 1", len(db.events))
			}
		})
	}
}

func TestPersistEventDuplicateOffsetIsIdempotent(t *testing.T) {
	db := newFakeDB()
	db.sensors["presence-1"] = fakeSensor{displayName: "presence-1", roomID: str("kitchen")}

	for range 2 {
		if err := persistEvent(context.Background(), db, v2Event("presence-1", 7)); err != nil {
			t.Fatalf("persistEvent() error = %v", err)
		}
	}

	if len(db.events) != 1 {
		t.Fatalf("events = %d, want 1", len(db.events))
	}
	if db.commits != 2 {
		t.Fatalf("commits = %d, want 2", db.commits)
	}
}

func TestSQLShape(t *testing.T) {
	if !strings.Contains(upsertSensorSQL, "ON CONFLICT (sensor_id) DO NOTHING") {
		t.Fatal("sensor upsert must be insert-only")
	}
	if strings.Contains(strings.ToUpper(upsertSensorSQL), "DO UPDATE") {
		t.Fatal("sensor upsert must never overwrite existing rows")
	}
	if !strings.Contains(insertEventSQL, "ON CONFLICT (kafka_topic, kafka_partition, kafka_offset)") {
		t.Fatal("event insert must be idempotent on kafka offset")
	}
}
