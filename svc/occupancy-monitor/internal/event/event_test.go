package event

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

func strPtr(value string) *string { return &value }

func requireString(t *testing.T, field string, got *string, want string) {
	t.Helper()
	if got == nil || *got != want {
		t.Fatalf("%s = %v, want %q", field, got, want)
	}
}

func requireNilString(t *testing.T, field string, got *string) {
	t.Helper()
	if got != nil {
		t.Fatalf("%s = %q, want nil", field, *got)
	}
}

func TestDecodeV1PRESENCE(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 1,
		"roomId": "kitchen",
		"deviceId": "presence-1",
		"type": "PRESENCE",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {"present": true}
	}`)

	got, err := Decode(payload, "event-data", 1, 42)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	if got.SchemaVersion != 1 {
		t.Fatalf("SchemaVersion = %d, want 1", got.SchemaVersion)
	}
	requireString(t, "RoomID", got.RoomID, "kitchen")
	requireString(t, "SensorID", got.SensorID, "presence-1")
	if got.EventType != EventTypePresence {
		t.Fatalf("EventType = %q, want PRESENCE", got.EventType)
	}
	if got.Present == nil || !*got.Present {
		t.Fatalf("Present = %v, want true", got.Present)
	}
	if got.Open != nil {
		t.Fatalf("Open = %v, want nil", got.Open)
	}
	if !got.ObservedAt.Equal(time.Date(2026, 10, 3, 8, 0, 0, 0, time.UTC)) {
		t.Fatalf("ObservedAt = %v", got.ObservedAt)
	}
	if got.KafkaTopic != "event-data" || got.KafkaPartition != 1 || got.KafkaOffset != 42 {
		t.Fatalf("Kafka metadata = %s/%d/%d", got.KafkaTopic, got.KafkaPartition, got.KafkaOffset)
	}
}

func TestDecodeV1OPENINGWithoutDeviceIDHasNoSensor(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 1,
		"roomId": "hallway",
		"type": "OPENING",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {"open": false}
	}`)

	got, err := Decode(payload, "event-data", 0, 7)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	if got.EventType != EventTypeOpening {
		t.Fatalf("EventType = %q, want OPENING", got.EventType)
	}
	requireString(t, "RoomID", got.RoomID, "hallway")
	requireNilString(t, "SensorID", got.SensorID)
	if got.Open == nil || *got.Open {
		t.Fatalf("Open = %v, want false", got.Open)
	}
	if got.Present != nil {
		t.Fatalf("Present = %v, want nil", got.Present)
	}
}

func TestDecodeV1TrimsDeviceIDAndTreatsBlankAsAbsent(t *testing.T) {
	tests := []struct {
		name     string
		deviceID string
		want     *string
	}{
		{name: "padded", deviceID: `"  presence-1 "`, want: strPtr("presence-1")},
		{name: "blank", deviceID: `"   "`, want: nil},
		{name: "null", deviceID: `null`, want: nil},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			payload := fmt.Sprintf(
				`{"schemaVersion":1,"roomId":" kitchen ","deviceId":%s,"type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
				tt.deviceID,
			)

			got, err := Decode([]byte(payload), "event-data", 0, 0)
			if err != nil {
				t.Fatalf("Decode() error = %v", err)
			}

			requireString(t, "RoomID", got.RoomID, "kitchen")
			if tt.want == nil {
				requireNilString(t, "SensorID", got.SensorID)
				return
			}
			requireString(t, "SensorID", got.SensorID, *tt.want)
		})
	}
}

func TestDecodeV2PRESENCE(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 2,
		"sensorId": "  presence-1 ",
		"type": "PRESENCE",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {"present": false}
	}`)

	got, err := Decode(payload, "event-data", 2, 99)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	if got.SchemaVersion != 2 {
		t.Fatalf("SchemaVersion = %d, want 2", got.SchemaVersion)
	}
	requireString(t, "SensorID", got.SensorID, "presence-1")
	requireNilString(t, "RoomID", got.RoomID)
	if got.EventType != EventTypePresence {
		t.Fatalf("EventType = %q, want PRESENCE", got.EventType)
	}
	if got.Present == nil || *got.Present {
		t.Fatalf("Present = %v, want false", got.Present)
	}
	if got.KafkaTopic != "event-data" || got.KafkaPartition != 2 || got.KafkaOffset != 99 {
		t.Fatalf("Kafka metadata = %s/%d/%d", got.KafkaTopic, got.KafkaPartition, got.KafkaOffset)
	}
}

func TestDecodeV2OPENING(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 2,
		"sensorId": "door-1",
		"type": "OPENING",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {"open": true}
	}`)

	got, err := Decode(payload, "event-data", 0, 1)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	requireString(t, "SensorID", got.SensorID, "door-1")
	if got.Open == nil || !*got.Open {
		t.Fatalf("Open = %v, want true", got.Open)
	}
}

func TestDecodeV2RejectsRoomIDAndDeviceID(t *testing.T) {
	withRoom := []byte(`{"schemaVersion":2,"sensorId":"presence-1","roomId":"kitchen","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`)
	if _, err := Decode(withRoom, "event-data", 0, 0); err == nil {
		t.Fatal("Decode() with roomId error = nil, want error")
	}

	withDevice := []byte(`{"schemaVersion":2,"sensorId":"presence-1","deviceId":"other-device","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`)
	if _, err := Decode(withDevice, "event-data", 0, 0); err == nil {
		t.Fatal("Decode() with deviceId error = nil, want error")
	}
}

func TestDecodeV2SensorIDLength(t *testing.T) {
	build := func(sensorID string) []byte {
		return []byte(fmt.Sprintf(
			`{"schemaVersion":2,"sensorId":%q,"type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
			sensorID,
		))
	}

	atLimit := strings.Repeat("a", 200)
	got, err := Decode(build(atLimit), "event-data", 0, 0)
	if err != nil {
		t.Fatalf("Decode() at limit error = %v", err)
	}
	requireString(t, "SensorID", got.SensorID, atLimit)

	if _, err := Decode(build(strings.Repeat("a", 201)), "event-data", 0, 0); err == nil {
		t.Fatal("Decode() over limit error = nil, want error")
	}
}

func TestDecodeValidationErrors(t *testing.T) {
	tests := []struct {
		name    string
		payload string
	}{
		{name: "bad json", payload: `{`},
		{
			name:    "unsupported schema",
			payload: `{"schemaVersion":3,"sensorId":"s","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "missing schema",
			payload: `{"roomId":"r","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "v1 blank room",
			payload: `{"schemaVersion":1,"roomId":" ","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "v1 unsupported type",
			payload: `{"schemaVersion":1,"roomId":"r","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "v1 presence missing present",
			payload: `{"schemaVersion":1,"roomId":"r","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "v1 opening missing open",
			payload: `{"schemaVersion":1,"roomId":"r","type":"OPENING","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "v2 missing sensorId",
			payload: `{"schemaVersion":2,"type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "v2 blank sensorId",
			payload: `{"schemaVersion":2,"sensorId":"  ","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "v2 only legacy roomId and deviceId",
			payload: `{"schemaVersion":2,"roomId":"r","deviceId":"d","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "v2 unsupported type",
			payload: `{"schemaVersion":2,"sensorId":"s","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "v2 presence missing present",
			payload: `{"schemaVersion":2,"sensorId":"s","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "v2 invalid observedAt",
			payload: `{"schemaVersion":2,"sensorId":"s","type":"PRESENCE","observedAt":"yesterday","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Decode([]byte(tt.payload), "event-data", 0, 0)
			if err == nil {
				t.Fatal("Decode() error = nil, want error")
			}
		})
	}
}
