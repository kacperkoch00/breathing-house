package event

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

const (
	SchemaVersion     = 1
	EventTypePresence = "PRESENCE"
	EventTypeOpening  = "OPENING"
)

// Event is the internal model persisted to occupancy.occupancy_event.
type Event struct {
	RoomID         string
	DeviceID       *string
	EventType      string
	Present        *bool
	Open           *bool
	ObservedAt     time.Time
	ReceivedAt     time.Time
	KafkaTopic     string
	KafkaPartition int32
	KafkaOffset    int64
}

type envelope struct {
	SchemaVersion int             `json:"schemaVersion"`
	RoomID        string          `json:"roomId"`
	DeviceID      *string         `json:"deviceId"`
	Type          string          `json:"type"`
	ObservedAt    string          `json:"observedAt"`
	ReceivedAt    string          `json:"receivedAt"`
	Values        json.RawMessage `json:"values"`
}

type presenceValues struct {
	Present *bool `json:"present"`
}

type openingValues struct {
	Open *bool `json:"open"`
}

// Decode validates a Kafka payload and maps it to an Event with Kafka metadata.
func Decode(payload []byte, topic string, partition int32, offset int64) (Event, error) {
	var env envelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return Event{}, fmt.Errorf("decode envelope: %w", err)
	}

	if env.SchemaVersion != SchemaVersion {
		return Event{}, fmt.Errorf("schemaVersion: want %d, got %d", SchemaVersion, env.SchemaVersion)
	}

	roomID := strings.TrimSpace(env.RoomID)
	if roomID == "" {
		return Event{}, fmt.Errorf("roomId: required")
	}

	if env.Type != EventTypePresence && env.Type != EventTypeOpening {
		return Event{}, fmt.Errorf("type: want %s or %s, got %q", EventTypePresence, EventTypeOpening, env.Type)
	}

	observedAt, err := parseRFC3339("observedAt", env.ObservedAt)
	if err != nil {
		return Event{}, err
	}

	receivedAt, err := parseRFC3339("receivedAt", env.ReceivedAt)
	if err != nil {
		return Event{}, err
	}

	event := Event{
		RoomID:         roomID,
		DeviceID:       normalizeOptionalString(env.DeviceID),
		EventType:      env.Type,
		ObservedAt:     observedAt,
		ReceivedAt:     receivedAt,
		KafkaTopic:     topic,
		KafkaPartition: partition,
		KafkaOffset:    offset,
	}

	switch env.Type {
	case EventTypePresence:
		if err := decodePresenceValues(env.Values, &event); err != nil {
			return Event{}, err
		}
	case EventTypeOpening:
		if err := decodeOpeningValues(env.Values, &event); err != nil {
			return Event{}, err
		}
	}

	return event, nil
}

func decodePresenceValues(raw json.RawMessage, event *Event) error {
	var values presenceValues
	if err := json.Unmarshal(raw, &values); err != nil {
		return fmt.Errorf("decode PRESENCE values: %w", err)
	}

	if values.Present == nil {
		return fmt.Errorf("PRESENCE values.present: required")
	}

	event.Present = values.Present
	return nil
}

func decodeOpeningValues(raw json.RawMessage, event *Event) error {
	var values openingValues
	if err := json.Unmarshal(raw, &values); err != nil {
		return fmt.Errorf("decode OPENING values: %w", err)
	}

	if values.Open == nil {
		return fmt.Errorf("OPENING values.open: required")
	}

	event.Open = values.Open
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
