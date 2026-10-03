package event

import (
	"testing"
	"time"
)

func TestDecodePRESENCE(t *testing.T) {
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

	if got.RoomID != "kitchen" {
		t.Fatalf("RoomID = %q, want kitchen", got.RoomID)
	}
	if got.DeviceID == nil || *got.DeviceID != "presence-1" {
		t.Fatalf("DeviceID = %v, want presence-1", got.DeviceID)
	}
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

func TestDecodeOPENING(t *testing.T) {
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
	if got.DeviceID != nil {
		t.Fatalf("DeviceID = %v, want nil", got.DeviceID)
	}
	if got.Open == nil || *got.Open {
		t.Fatalf("Open = %v, want false", got.Open)
	}
	if got.Present != nil {
		t.Fatalf("Present = %v, want nil", got.Present)
	}
}

func TestDecodeValidationErrors(t *testing.T) {
	tests := []struct {
		name    string
		payload string
	}{
		{name: "bad json", payload: `{`},
		{
			name:    "wrong schema",
			payload: `{"schemaVersion":2,"roomId":"r","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "blank room",
			payload: `{"schemaVersion":1,"roomId":" ","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"present":true}}`,
		},
		{
			name:    "unsupported type",
			payload: `{"schemaVersion":1,"roomId":"r","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "presence missing present",
			payload: `{"schemaVersion":1,"roomId":"r","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "opening missing open",
			payload: `{"schemaVersion":1,"roomId":"r","type":"OPENING","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
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
