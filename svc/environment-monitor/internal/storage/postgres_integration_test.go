package storage

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"environment-monitor/internal/reading"
)

// Runs against a real Postgres when TEST_DATABASE_URL is set, for example:
//
//	podman run --rm -d -p 55432:5432 -e POSTGRES_PASSWORD=bh postgres:16
//	TEST_DATABASE_URL=postgres://postgres:bh@localhost:55432/postgres go test ./internal/storage
func TestInsertReadingAgainstPostgres(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}

	ctx := context.Background()
	pool, err := NewPool(ctx, url)
	if err != nil {
		t.Fatalf("NewPool() error = %v", err)
	}
	defer pool.Close()

	initSQL, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "deploy", "k8s", "postgres-init.sql"))
	if err != nil {
		t.Fatalf("read postgres-init.sql: %v", err)
	}
	if _, err := pool.pool.Exec(ctx, string(initSQL)); err != nil {
		t.Fatalf("apply postgres-init.sql: %v", err)
	}

	suffix := time.Now().UnixNano()
	topic := fmt.Sprintf("it-topic-%d", suffix)
	sensorID := fmt.Sprintf("it-sensor-%d", suffix)
	roomID := fmt.Sprintf("it-room-%d", suffix)
	otherRoomID := fmt.Sprintf("it-room-b-%d", suffix)

	t.Cleanup(func() {
		_, _ = pool.pool.Exec(ctx, `DELETE FROM environment.environment_reading WHERE kafka_topic = $1`, topic)
		_, _ = pool.pool.Exec(ctx, `DELETE FROM home_api.sensor WHERE sensor_id = $1`, sensorID)
		_, _ = pool.pool.Exec(ctx, `DELETE FROM home_api.room WHERE room_id = ANY($1)`, []string{roomID, otherRoomID})
	})

	insert := func(offset int64) {
		t.Helper()
		r := reading.Reading{
			SensorID:    &sensorID,
			SensorType:  reading.SensorTypeAir,
			Temperature: ptr(21.5),
			Humidity:    ptr(40.0),
			CO2:         ptr(600.0),
			ObservedAt:  time.Now().UTC(),
			ReceivedAt:  time.Now().UTC(),
			KafkaTopic:  topic,
			KafkaOffset: offset,
		}
		if err := pool.InsertReading(ctx, r); err != nil {
			t.Fatalf("InsertReading(%d) error = %v", offset, err)
		}
	}
	readingRoom := func(offset int64) (room, sensor *string) {
		t.Helper()
		err := pool.pool.QueryRow(ctx,
			`SELECT room_id, sensor_id FROM environment.environment_reading WHERE kafka_topic = $1 AND kafka_offset = $2`,
			topic, offset).Scan(&room, &sensor)
		if err != nil {
			t.Fatalf("select reading %d: %v", offset, err)
		}
		return room, sensor
	}

	insert(1)
	var displayName string
	var sensorRoom *string
	if err := pool.pool.QueryRow(ctx, `SELECT display_name, room_id FROM home_api.sensor WHERE sensor_id = $1`, sensorID).Scan(&displayName, &sensorRoom); err != nil {
		t.Fatalf("select sensor: %v", err)
	}
	if displayName != sensorID || sensorRoom != nil {
		t.Fatalf("sensor = %q/%v, want auto-registered unassigned %q", displayName, sensorRoom, sensorID)
	}
	if room, sensor := readingRoom(1); room != nil || sensor == nil || *sensor != sensorID {
		t.Fatalf("reading 1 room/sensor = %v/%v", room, sensor)
	}

	for _, id := range []string{roomID, otherRoomID} {
		if _, err := pool.pool.Exec(ctx, `INSERT INTO home_api.room (room_id, name) VALUES ($1, $1)`, id); err != nil {
			t.Fatalf("insert room: %v", err)
		}
	}
	if _, err := pool.pool.Exec(ctx, `UPDATE home_api.sensor SET display_name = 'Edited', room_id = $2 WHERE sensor_id = $1`, sensorID, roomID); err != nil {
		t.Fatalf("assign sensor: %v", err)
	}
	insert(2)

	if _, err := pool.pool.Exec(ctx, `UPDATE home_api.sensor SET room_id = $2 WHERE sensor_id = $1`, sensorID, otherRoomID); err != nil {
		t.Fatalf("move sensor: %v", err)
	}
	insert(3)
	insert(3)

	if err := pool.pool.QueryRow(ctx, `SELECT display_name FROM home_api.sensor WHERE sensor_id = $1`, sensorID).Scan(&displayName); err != nil {
		t.Fatalf("select sensor: %v", err)
	}
	if displayName != "Edited" {
		t.Fatalf("display_name = %q, want Edited", displayName)
	}

	if room, _ := readingRoom(1); room != nil {
		t.Fatalf("reading 1 room = %q, want NULL", *room)
	}
	if room, _ := readingRoom(2); room == nil || *room != roomID {
		t.Fatalf("reading 2 room = %v, want %s", room, roomID)
	}
	if room, _ := readingRoom(3); room == nil || *room != otherRoomID {
		t.Fatalf("reading 3 room = %v, want %s", room, otherRoomID)
	}

	var count int
	if err := pool.pool.QueryRow(ctx, `SELECT count(*) FROM environment.environment_reading WHERE kafka_topic = $1`, topic).Scan(&count); err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 3 {
		t.Fatalf("readings = %d, want 3 (duplicate offset ignored)", count)
	}

	longID := fmt.Sprintf("%0*d", 200, suffix)
	t.Cleanup(func() {
		_, _ = pool.pool.Exec(ctx, `DELETE FROM environment.environment_reading WHERE sensor_id = $1`, longID)
		_, _ = pool.pool.Exec(ctx, `DELETE FROM home_api.sensor WHERE sensor_id = $1`, longID)
	})
	err = pool.InsertReading(ctx, reading.Reading{
		SensorID: &longID, SensorType: reading.SensorTypeAir,
		ObservedAt: time.Now().UTC(), ReceivedAt: time.Now().UTC(),
		KafkaTopic: topic, KafkaOffset: 4,
	})
	if err != nil {
		t.Fatalf("InsertReading() with 200-char sensorId error = %v", err)
	}
}

func ptr[T any](v T) *T { return &v }
