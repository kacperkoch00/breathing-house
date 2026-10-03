package reading

import (
	"strings"
	"testing"
	"time"
)

func TestDecodeAIR(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 1,
		"roomId": "living-room",
		"deviceId": "sensor-1",
		"type": "AIR",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {
			"temperature": 22.5,
			"humidity": 45,
			"co2": 700
		}
	}`)

	got, err := Decode(payload, "sensor-data", 2, 99)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	assertString(t, "RoomID", got.RoomID, "living-room")
	assertString(t, "SensorID", got.SensorID, "sensor-1")
	if got.SensorType != SensorTypeAir {
		t.Fatalf("SensorType = %q, want AIR", got.SensorType)
	}
	if got.Temperature == nil || *got.Temperature != 22.5 {
		t.Fatalf("Temperature = %v, want 22.5", got.Temperature)
	}
	if got.Humidity == nil || *got.Humidity != 45 {
		t.Fatalf("Humidity = %v, want 45", got.Humidity)
	}
	if got.CO2 == nil || *got.CO2 != 700 {
		t.Fatalf("CO2 = %v, want 700", got.CO2)
	}
	if got.Light != nil || got.LightLevel != nil {
		t.Fatalf("AIR light fields must be nil, got light=%v lightLevel=%v", got.Light, got.LightLevel)
	}
	if !got.ObservedAt.Equal(time.Date(2026, 10, 3, 8, 0, 0, 0, time.UTC)) {
		t.Fatalf("ObservedAt = %v", got.ObservedAt)
	}
	if !got.ReceivedAt.Equal(time.Date(2026, 10, 3, 8, 0, 1, 0, time.UTC)) {
		t.Fatalf("ReceivedAt = %v", got.ReceivedAt)
	}
	if got.KafkaTopic != "sensor-data" || got.KafkaPartition != 2 || got.KafkaOffset != 99 {
		t.Fatalf("Kafka metadata = %s/%d/%d", got.KafkaTopic, got.KafkaPartition, got.KafkaOffset)
	}
}

func TestDecodeROOM(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 1,
		"roomId": "kitchen",
		"type": "ROOM",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {
			"temperature": 21,
			"light": 120,
			"lightLevel": "NORMAL"
		}
	}`)

	got, err := Decode(payload, "sensor-data", 0, 1)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	if got.SensorType != SensorTypeRoom {
		t.Fatalf("SensorType = %q, want ROOM", got.SensorType)
	}
	assertString(t, "RoomID", got.RoomID, "kitchen")
	if got.SensorID != nil {
		t.Fatalf("SensorID = %v, want nil", got.SensorID)
	}
	if got.Temperature == nil || *got.Temperature != 21 {
		t.Fatalf("Temperature = %v, want 21", got.Temperature)
	}
	if got.Light == nil || *got.Light != 120 {
		t.Fatalf("Light = %v, want 120", got.Light)
	}
	if got.LightLevel == nil || *got.LightLevel != "NORMAL" {
		t.Fatalf("LightLevel = %v, want NORMAL", got.LightLevel)
	}
	if got.Humidity != nil || got.CO2 != nil {
		t.Fatalf("ROOM humidity/co2 must be nil, got humidity=%v co2=%v", got.Humidity, got.CO2)
	}
}

func TestDecodeV1TrimsDeviceIDAndRoomID(t *testing.T) {
	payload := []byte(`{"schemaVersion":1,"roomId":" kitchen ","deviceId":"  sensor-1 ","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":2,"co2":3}}`)

	got, err := Decode(payload, "sensor-data", 0, 1)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	assertString(t, "SensorID", got.SensorID, "sensor-1")
	assertString(t, "RoomID", got.RoomID, "kitchen")
}

func TestDecodeV1BlankDeviceIDMeansNoSensor(t *testing.T) {
	payload := []byte(`{"schemaVersion":1,"roomId":"kitchen","deviceId":"   ","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":2,"co2":3}}`)

	got, err := Decode(payload, "sensor-data", 0, 1)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	if got.SensorID != nil {
		t.Fatalf("SensorID = %v, want nil", got.SensorID)
	}
	assertString(t, "RoomID", got.RoomID, "kitchen")
}

func TestDecodeV2AIR(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 2,
		"sensorId": "  sensor-9 ",
		"type": "AIR",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {"temperature": 22.5, "humidity": 45, "co2": 700}
	}`)

	got, err := Decode(payload, "sensor-data", 1, 5)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	assertString(t, "SensorID", got.SensorID, "sensor-9")
	if got.RoomID != nil {
		t.Fatalf("RoomID = %v, want nil (resolved at persist time)", *got.RoomID)
	}
	if got.SensorType != SensorTypeAir {
		t.Fatalf("SensorType = %q, want AIR", got.SensorType)
	}
	if got.CO2 == nil || *got.CO2 != 700 {
		t.Fatalf("CO2 = %v, want 700", got.CO2)
	}
	if got.KafkaTopic != "sensor-data" || got.KafkaPartition != 1 || got.KafkaOffset != 5 {
		t.Fatalf("Kafka metadata = %s/%d/%d", got.KafkaTopic, got.KafkaPartition, got.KafkaOffset)
	}
}

func TestDecodeV2ROOM(t *testing.T) {
	payload := []byte(`{
		"schemaVersion": 2,
		"sensorId": "room-sensor",
		"type": "ROOM",
		"observedAt": "2026-10-03T08:00:00Z",
		"receivedAt": "2026-10-03T08:00:01Z",
		"values": {"temperature": 21, "light": 120, "lightLevel": "NORMAL"}
	}`)

	got, err := Decode(payload, "sensor-data", 0, 1)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}

	assertString(t, "SensorID", got.SensorID, "room-sensor")
	assertString(t, "LightLevel", got.LightLevel, "NORMAL")
	if got.RoomID != nil {
		t.Fatalf("RoomID = %v, want nil", *got.RoomID)
	}
}

func TestDecodeV2AcceptsMaxLengthSensorID(t *testing.T) {
	id := strings.Repeat("é", MaxSensorIDLength)
	payload := []byte(`{"schemaVersion":2,"sensorId":"` + id + `","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`)

	got, err := Decode(payload, "sensor-data", 0, 1)
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	assertString(t, "SensorID", got.SensorID, id)
}

func TestDecodeValidationErrors(t *testing.T) {
	tests := []struct {
		name    string
		payload string
	}{
		{
			name:    "bad json",
			payload: `{`,
		},
		{
			name:    "unsupported schema",
			payload: `{"schemaVersion":3,"sensorId":"s","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "v1 missing room",
			payload: `{"schemaVersion":1,"deviceId":"s","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "v2 missing sensorId",
			payload: `{"schemaVersion":2,"type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "v2 blank sensorId",
			payload: `{"schemaVersion":2,"sensorId":"  ","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "v2 sensorId too long",
			payload: `{"schemaVersion":2,"sensorId":"` + strings.Repeat("a", MaxSensorIDLength+1) + `","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "v2 with roomId",
			payload: `{"schemaVersion":2,"sensorId":"s","roomId":"kitchen","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "v2 with deviceId",
			payload: `{"schemaVersion":2,"sensorId":"s","deviceId":"d","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "v2 unsupported type",
			payload: `{"schemaVersion":2,"sensorId":"s","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "blank room",
			payload: `{"schemaVersion":1,"roomId":" ","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "unsupported type",
			payload: `{"schemaVersion":1,"roomId":"r","type":"PRESENCE","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{}}`,
		},
		{
			name:    "missing observedAt",
			payload: `{"schemaVersion":1,"roomId":"r","type":"AIR","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"humidity":1,"co2":1}}`,
		},
		{
			name:    "air missing humidity",
			payload: `{"schemaVersion":1,"roomId":"r","type":"AIR","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"co2":1}}`,
		},
		{
			name:    "room blank lightLevel",
			payload: `{"schemaVersion":1,"roomId":"r","type":"ROOM","observedAt":"2026-10-03T08:00:00Z","receivedAt":"2026-10-03T08:00:01Z","values":{"temperature":1,"light":1,"lightLevel":" "}}`,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := Decode([]byte(tt.payload), "sensor-data", 0, 0)
			if err == nil {
				t.Fatal("Decode() error = nil, want error")
			}
		})
	}
}

func assertString(t *testing.T, field string, got *string, want string) {
	t.Helper()
	if got == nil || *got != want {
		t.Fatalf("%s = %v, want %q", field, got, want)
	}
}
