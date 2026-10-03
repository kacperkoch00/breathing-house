package storage

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Runs against a real database initialised with deploy/k8s/postgres-init.sql.
// Set OCCUPANCY_TEST_DATABASE_URL to enable.
func TestPostgresInsertEventSensorRoomSnapshots(t *testing.T) {
	url := os.Getenv("OCCUPANCY_TEST_DATABASE_URL")
	if url == "" {
		t.Skip("OCCUPANCY_TEST_DATABASE_URL not set")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	pool, err := NewPool(ctx, url)
	if err != nil {
		t.Fatalf("NewPool() error = %v", err)
	}
	defer pool.Close()

	raw, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatalf("pgxpool.New() error = %v", err)
	}
	defer raw.Close()

	const sensorID = "it-presence-1"
	cleanup := func() {
		_, _ = raw.Exec(ctx, `DELETE FROM occupancy.occupancy_event WHERE sensor_id = $1 OR kafka_topic = 'it-topic'`, sensorID)
		_, _ = raw.Exec(ctx, `DELETE FROM home_api.sensor WHERE sensor_id = $1`, sensorID)
		_, _ = raw.Exec(ctx, `DELETE FROM home_api.room WHERE room_id IN ('it-kitchen', 'it-hallway')`)
	}
	cleanup()
	t.Cleanup(cleanup)

	for _, room := range []string{"it-kitchen", "it-hallway"} {
		if _, err := raw.Exec(ctx, `INSERT INTO home_api.room (room_id, name) VALUES ($1, $1)`, room); err != nil {
			t.Fatalf("insert room %s: %v", room, err)
		}
	}

	insert := func(offset int64) {
		t.Helper()
		e := v2Event(sensorID, offset)
		e.KafkaTopic = "it-topic"
		if err := pool.InsertEvent(ctx, e); err != nil {
			t.Fatalf("InsertEvent(%d) error = %v", offset, err)
		}
	}

	insert(1)

	var displayName string
	var sensorRoom *string
	if err := raw.QueryRow(ctx, `SELECT display_name, room_id FROM home_api.sensor WHERE sensor_id = $1`, sensorID).Scan(&displayName, &sensorRoom); err != nil {
		t.Fatalf("select sensor: %v", err)
	}
	if displayName != sensorID || sensorRoom != nil {
		t.Fatalf("sensor = (%q, %v), want (%q, nil)", displayName, sensorRoom, sensorID)
	}

	if _, err := raw.Exec(ctx, `UPDATE home_api.sensor SET display_name = 'Kitchen motion', room_id = 'it-kitchen' WHERE sensor_id = $1`, sensorID); err != nil {
		t.Fatalf("update sensor: %v", err)
	}
	insert(2)
	insert(2)

	if _, err := raw.Exec(ctx, `UPDATE home_api.sensor SET room_id = 'it-hallway' WHERE sensor_id = $1`, sensorID); err != nil {
		t.Fatalf("move sensor: %v", err)
	}
	insert(3)

	if err := raw.QueryRow(ctx, `SELECT display_name FROM home_api.sensor WHERE sensor_id = $1`, sensorID).Scan(&displayName); err != nil {
		t.Fatalf("select sensor: %v", err)
	}
	if displayName != "Kitchen motion" {
		t.Fatalf("display_name = %q, want Kitchen motion", displayName)
	}

	rows, err := raw.Query(ctx, `SELECT kafka_offset, room_id, sensor_id FROM occupancy.occupancy_event WHERE kafka_topic = 'it-topic' ORDER BY kafka_offset`)
	if err != nil {
		t.Fatalf("select events: %v", err)
	}
	defer rows.Close()

	type snapshot struct {
		offset   int64
		roomID   *string
		sensorID *string
	}
	var got []snapshot
	for rows.Next() {
		var s snapshot
		if err := rows.Scan(&s.offset, &s.roomID, &s.sensorID); err != nil {
			t.Fatalf("scan: %v", err)
		}
		got = append(got, s)
	}

	want := []*string{nil, str("it-kitchen"), str("it-hallway")}
	if len(got) != len(want) {
		t.Fatalf("events = %d, want %d (duplicate offset must be ignored)", len(got), len(want))
	}
	for i, s := range got {
		requireStringPtr(t, "room_id", s.roomID, want[i])
		requireStringPtr(t, "sensor_id", s.sensorID, str(sensorID))
	}
}
