package reading

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	SchemaVersionV1 = 1
	SchemaVersionV2 = 2
	SensorTypeRoom  = "ROOM"
	SensorTypeAir   = "AIR"

	// MaxSensorIDLength is the maximum length of a schema-v2 sensorId.
	MaxSensorIDLength = 200
)

// Reading is the internal model persisted to environment.environment_reading.
//
// SensorID is the stable sensor identity. RoomID is a nullable snapshot: for
// schema v1 it is the envelope's legacy roomId; for schema v2 it is nil at
// decode time and resolved from home_api.sensor when the reading is persisted.
type Reading struct {
	SensorID       *string
	RoomID         *string
	SensorType     string
	Temperature    *float64
	Humidity       *float64
	CO2            *float64
	Light          *float64
	LightLevel     *string
	ObservedAt     time.Time
	ReceivedAt     time.Time
	KafkaTopic     string
	KafkaPartition int32
	KafkaOffset    int64
}

type envelope struct {
	SchemaVersion int             `json:"schemaVersion"`
	SensorID      *string         `json:"sensorId"`
	RoomID        *string         `json:"roomId"`
	DeviceID      *string         `json:"deviceId"`
	Type          string          `json:"type"`
	ObservedAt    string          `json:"observedAt"`
	ReceivedAt    string          `json:"receivedAt"`
	Values        json.RawMessage `json:"values"`
}

type roomValues struct {
	Temperature *float64 `json:"temperature"`
	Light       *float64 `json:"light"`
	LightLevel  *string  `json:"lightLevel"`
}

type airValues struct {
	Temperature *float64 `json:"temperature"`
	Humidity    *float64 `json:"humidity"`
	CO2         *float64 `json:"co2"`
}

// Decode validates a Kafka payload and maps it to a Reading with Kafka metadata.
func Decode(payload []byte, topic string, partition int32, offset int64) (Reading, error) {
	var env envelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return Reading{}, fmt.Errorf("decode envelope: %w", err)
	}

	ids, err := decodeIdentity(env)
	if err != nil {
		return Reading{}, err
	}

	if env.Type != SensorTypeRoom && env.Type != SensorTypeAir {
		return Reading{}, fmt.Errorf("type: want %s or %s, got %q", SensorTypeRoom, SensorTypeAir, env.Type)
	}

	observedAt, err := parseRFC3339("observedAt", env.ObservedAt)
	if err != nil {
		return Reading{}, err
	}

	receivedAt, err := parseRFC3339("receivedAt", env.ReceivedAt)
	if err != nil {
		return Reading{}, err
	}

	reading := Reading{
		SensorID:       ids.sensorID,
		RoomID:         ids.roomID,
		SensorType:     env.Type,
		ObservedAt:     observedAt,
		ReceivedAt:     receivedAt,
		KafkaTopic:     topic,
		KafkaPartition: partition,
		KafkaOffset:    offset,
	}

	switch env.Type {
	case SensorTypeRoom:
		if err := decodeRoomValues(env.Values, &reading); err != nil {
			return Reading{}, err
		}
	case SensorTypeAir:
		if err := decodeAirValues(env.Values, &reading); err != nil {
			return Reading{}, err
		}
	}

	return reading, nil
}

type identities struct {
	sensorID *string
	roomID   *string
}

func decodeIdentity(env envelope) (identities, error) {
	switch env.SchemaVersion {
	case SchemaVersionV1:
		if env.RoomID == nil || strings.TrimSpace(*env.RoomID) == "" {
			return identities{}, fmt.Errorf("roomId: required")
		}
		roomID := strings.TrimSpace(*env.RoomID)
		return identities{
			sensorID: normalizeOptionalString(env.DeviceID),
			roomID:   &roomID,
		}, nil
	case SchemaVersionV2:
		if env.RoomID != nil {
			return identities{}, fmt.Errorf("roomId: not allowed in schemaVersion %d", SchemaVersionV2)
		}
		if env.DeviceID != nil {
			return identities{}, fmt.Errorf("deviceId: not allowed in schemaVersion %d", SchemaVersionV2)
		}
		sensorID := normalizeOptionalString(env.SensorID)
		if sensorID == nil {
			return identities{}, fmt.Errorf("sensorId: required")
		}
		if utf8.RuneCountInString(*sensorID) > MaxSensorIDLength {
			return identities{}, fmt.Errorf("sensorId: longer than %d characters", MaxSensorIDLength)
		}
		return identities{sensorID: sensorID}, nil
	default:
		return identities{}, fmt.Errorf("schemaVersion: want %d or %d, got %d", SchemaVersionV1, SchemaVersionV2, env.SchemaVersion)
	}
}

func decodeRoomValues(raw json.RawMessage, reading *Reading) error {
	var values roomValues
	if err := json.Unmarshal(raw, &values); err != nil {
		return fmt.Errorf("decode ROOM values: %w", err)
	}

	if values.Temperature == nil {
		return fmt.Errorf("ROOM values.temperature: required")
	}
	if values.Light == nil {
		return fmt.Errorf("ROOM values.light: required")
	}

	lightLevel := normalizeOptionalString(values.LightLevel)
	if lightLevel == nil {
		return fmt.Errorf("ROOM values.lightLevel: required")
	}

	reading.Temperature = values.Temperature
	reading.Light = values.Light
	reading.LightLevel = lightLevel
	return nil
}

func decodeAirValues(raw json.RawMessage, reading *Reading) error {
	var values airValues
	if err := json.Unmarshal(raw, &values); err != nil {
		return fmt.Errorf("decode AIR values: %w", err)
	}

	if values.Temperature == nil {
		return fmt.Errorf("AIR values.temperature: required")
	}
	if values.Humidity == nil {
		return fmt.Errorf("AIR values.humidity: required")
	}
	if values.CO2 == nil {
		return fmt.Errorf("AIR values.co2: required")
	}

	reading.Temperature = values.Temperature
	reading.Humidity = values.Humidity
	reading.CO2 = values.CO2
	return nil
}

func parseRFC3339(field, value string) (time.Time, error) {
	if strings.TrimSpace(value) == "" {
		return time.Time{}, fmt.Errorf("%s: required", field)
	}

	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		return time.Time{}, fmt.Errorf("%s: invalid RFC3339 timestamp: %w", field, err)
	}

	return parsed, nil
}

func normalizeOptionalString(value *string) *string {
	if value == nil {
		return nil
	}

	trimmed := strings.TrimSpace(*value)
	if trimmed == "" {
		return nil
	}

	return &trimmed
}
