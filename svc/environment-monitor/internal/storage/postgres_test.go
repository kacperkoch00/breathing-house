package storage

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"environment-monitor/internal/reading"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type fakeSensor struct {
	displayName string
	roomID      *string
}

type fakeStoredReading struct {
	roomID   *string
	sensorID any
	offset   int64
}

// fakeDB models the three tables touched by persistReading, with
// transactional rollback and the SQL semantics the service relies on.
type fakeDB struct {
	sensors  map[string]*fakeSensor
	readings []fakeStoredReading
	offsets  map[int64]bool

	failOn string
	execs  []string
}

func newFakeDB() *fakeDB {
	return &fakeDB{sensors: map[string]*fakeSensor{}, offsets: map[int64]bool{}}
}

func (db *fakeDB) snapshot() *fakeDB {
	cp := newFakeDB()
	for k, v := range db.sensors {
		s := *v
		cp.sensors[k] = &s
	}
	cp.readings = append(cp.readings, db.readings...)
	for k, v := range db.offsets {
		cp.offsets[k] = v
	}
	return cp
}

func (db *fakeDB) restore(from *fakeDB) {
	db.sensors = from.sensors
	db.readings = from.readings
	db.offsets = from.offsets
}

func (db *fakeDB) inTx(ctx context.Context, fn func(executor) error) error {
	before := db.snapshot()
	if err := fn(db); err != nil {
		db.restore(before)
		return err
	}
	return nil
}

func (db *fakeDB) Exec(_ context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	db.execs = append(db.execs, sql)
	if db.failOn != "" && sql == db.failOn {
		return pgconn.CommandTag{}, errors.New("db failure")
	}

	switch sql {
	case upsertSensorSQL:
		id := args[0].(string)
		if _, ok := db.sensors[id]; !ok {
			db.sensors[id] = &fakeSensor{displayName: id}
		}
	case insertReadingSQL:
		offset := args[len(args)-1].(int64)
		if db.offsets[offset] {
			return pgconn.NewCommandTag("INSERT 0 0"), nil
		}
		db.offsets[offset] = true
		db.readings = append(db.readings, fakeStoredReading{
			roomID:   args[0].(*string),
			sensorID: args[1],
			offset:   offset,
		})
	default:
		return pgconn.CommandTag{}, errors.New("unexpected exec: " + sql)
	}
	return pgconn.NewCommandTag("INSERT 0 1"), nil
}

type fakeRow struct {
	roomID *string
	err    error
}

func (r fakeRow) Scan(dest ...any) error {
	if r.err != nil {
		return r.err
	}
	*(dest[0].(**string)) = r.roomID
	return nil
}

func (db *fakeDB) QueryRow(_ context.Context, sql string, args ...any) pgx.Row {
	if sql != selectSensorRoomSQL {
		return fakeRow{err: errors.New("unexpected query: " + sql)}
	}
	sensor, ok := db.sensors[args[0].(string)]
	if !ok {
		return fakeRow{err: pgx.ErrNoRows}
	}
	return fakeRow{roomID: sensor.roomID}
}

func str(v string) *string { return &v }

func v2Reading(sensorID string, offset int64) reading.Reading {
	return reading.Reading{
		SensorID:    str(sensorID),
		SensorType:  reading.SensorTypeAir,
		ObservedAt:  time.Date(2026, 10, 3, 8, 0, 0, 0, time.UTC),
		ReceivedAt:  time.Date(2026, 10, 3, 8, 0, 1, 0, time.UTC),
		KafkaTopic:  "sensor-data",
		KafkaOffset: offset,
	}
}

func newPool(db *fakeDB) *Pool {
	return &Pool{inTx: db.inTx}
}

func TestInsertReadingRegistersNewSensorWithDisplayNameEqualToID(t *testing.T) {
	db := newFakeDB()

	if err := newPool(db).InsertReading(context.Background(), v2Reading("sensor-1", 1)); err != nil {
		t.Fatalf("InsertReading() error = %v", err)
	}

	sensor, ok := db.sensors["sensor-1"]
	if !ok {
		t.Fatal("sensor-1 was not registered")
	}
	if sensor.displayName != "sensor-1" {
		t.Fatalf("displayName = %q, want sensor-1", sensor.displayName)
	}
	if len(db.readings) != 1 {
		t.Fatalf("readings = %d, want 1", len(db.readings))
	}
	got := db.readings[0]
	if got.sensorID.(*string) == nil || *got.sensorID.(*string) != "sensor-1" {
		t.Fatalf("sensor_id = %v, want sensor-1", got.sensorID)
	}
}

func TestInsertReadingDoesNotOverwriteEditedDisplayName(t *testing.T) {
	db := newFakeDB()
	db.sensors["sensor-1"] = &fakeSensor{displayName: "Kitchen CO2"}

	if err := newPool(db).InsertReading(context.Background(), v2Reading("sensor-1", 1)); err != nil {
		t.Fatalf("InsertReading() error = %v", err)
	}

	if got := db.sensors["sensor-1"].displayName; got != "Kitchen CO2" {
		t.Fatalf("displayName = %q, want Kitchen CO2", got)
	}
}

func TestUpsertSensorSQLNeverUpdates(t *testing.T) {
	for _, forbidden := range []string{"DO UPDATE", "display_name ="} {
		if strings.Contains(upsertSensorSQL, forbidden) {
			t.Fatalf("upsertSensorSQL must not contain %q:\n%s", forbidden, upsertSensorSQL)
		}
	}
	if !strings.Contains(upsertSensorSQL, "ON CONFLICT (sensor_id) DO NOTHING") {
		t.Fatalf("upsertSensorSQL must be ON CONFLICT DO NOTHING:\n%s", upsertSensorSQL)
	}
}

func TestInsertReadingSnapshotsAssignedRoom(t *testing.T) {
	db := newFakeDB()
	db.sensors["sensor-1"] = &fakeSensor{displayName: "sensor-1", roomID: str("kitchen")}

	if err := newPool(db).InsertReading(context.Background(), v2Reading("sensor-1", 1)); err != nil {
		t.Fatalf("InsertReading() error = %v", err)
	}

	got := db.readings[0].roomID
	if got == nil || *got != "kitchen" {
		t.Fatalf("room_id = %v, want kitchen", got)
	}
}

func TestInsertReadingStoresNullRoomForUnassignedSensor(t *testing.T) {
	db := newFakeDB()

	if err := newPool(db).InsertReading(context.Background(), v2Reading("sensor-1", 1)); err != nil {
		t.Fatalf("InsertReading() error = %v", err)
	}

	if got := db.readings[0].roomID; got != nil {
		t.Fatalf("room_id = %q, want NULL", *got)
	}
}

func TestMovingSensorAffectsOnlyLaterReadings(t *testing.T) {
	db := newFakeDB()
	pool := newPool(db)
	ctx := context.Background()

	db.sensors["sensor-1"] = &fakeSensor{displayName: "sensor-1", roomID: str("kitchen")}
	if err := pool.InsertReading(ctx, v2Reading("sensor-1", 1)); err != nil {
		t.Fatalf("InsertReading() error = %v", err)
	}

	db.sensors["sensor-1"].roomID = str("bedroom")
	if err := pool.InsertReading(ctx, v2Reading("sensor-1", 2)); err != nil {
		t.Fatalf("InsertReading() error = %v", err)
	}

	db.sensors["sensor-1"].roomID = nil
	if err := pool.InsertReading(ctx, v2Reading("sensor-1", 3)); err != nil {
		t.Fatalf("InsertReading() error = %v", err)
	}

	if got := db.readings[0].roomID; got == nil || *got != "kitchen" {
		t.Fatalf("reading 1 room_id = %v, want kitchen (unchanged)", got)
	}
	if got := db.readings[1].roomID; got == nil || *got != "bedroom" {
		t.Fatalf("reading 2 room_id = %v, want bedroom", got)
	}
	if got := db.readings[2].roomID; got != nil {
		t.Fatalf("reading 3 room_id = %q, want NULL", *got)
	}
}

func TestInsertReadingDuplicateOffsetIsIdempotent(t *testing.T) {
	db := newFakeDB()
	pool := newPool(db)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		if err := pool.InsertReading(ctx, v2Reading("sensor-1", 7)); err != nil {
			t.Fatalf("InsertReading() #%d error = %v", i, err)
		}
	}

	if len(db.readings) != 1 {
		t.Fatalf("readings = %d, want 1", len(db.readings))
	}
}

func TestInsertReadingRollsBackSensorWhenReadingInsertFails(t *testing.T) {
	db := newFakeDB()
	db.failOn = insertReadingSQL

	err := newPool(db).InsertReading(context.Background(), v2Reading("sensor-1", 1))
	if err == nil {
		t.Fatal("InsertReading() error = nil, want error")
	}

	if len(db.sensors) != 0 || len(db.readings) != 0 {
		t.Fatalf("transaction not rolled back: sensors=%v readings=%v", db.sensors, db.readings)
	}
}

func TestInsertReadingReturnsErrorWhenSensorUpsertFails(t *testing.T) {
	db := newFakeDB()
	db.failOn = upsertSensorSQL

	err := newPool(db).InsertReading(context.Background(), v2Reading("sensor-1", 1))
	if err == nil {
		t.Fatal("InsertReading() error = nil, want error")
	}
	if len(db.readings) != 0 {
		t.Fatalf("readings = %v, want none", db.readings)
	}
}

func TestInsertReadingReturnsBeginError(t *testing.T) {
	wantErr := errors.New("cannot begin")
	pool := &Pool{inTx: func(context.Context, func(executor) error) error { return wantErr }}

	err := pool.InsertReading(context.Background(), v2Reading("sensor-1", 1))
	if !errors.Is(err, wantErr) {
		t.Fatalf("InsertReading() error = %v, want %v", err, wantErr)
	}
}
