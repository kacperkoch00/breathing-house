package storage

import (
	"context"
	"fmt"

	"environment-monitor/internal/reading"

	"github.com/jackc/pgx/v5/pgxpool"
)

const insertReadingSQL = `
INSERT INTO environment.environment_reading (
  room_id,
  device_id,
  sensor_type,
  temperature,
  humidity,
  co2,
  light,
  light_level,
  observed_at,
  received_at,
  kafka_topic,
  kafka_partition,
  kafka_offset
)
VALUES (
  $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13
)
ON CONFLICT (kafka_topic, kafka_partition, kafka_offset)
DO NOTHING;
`

// Pool wraps a pgx connection pool for environment readings.
type Pool struct {
	pool *pgxpool.Pool
}

// NewPool creates a PostgreSQL pool from databaseURL. Does not require the
// database to be reachable; connection happens on first use.
func NewPool(ctx context.Context, databaseURL string) (*Pool, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("create postgres pool: %w", err)
	}

	return &Pool{pool: pool}, nil
}

// Close closes the underlying pool.
func (p *Pool) Close() {
	p.pool.Close()
}

// Ping checks database connectivity.
func (p *Pool) Ping(ctx context.Context) error {
	return p.pool.Ping(ctx)
}

// InsertReading appends a reading, ignoring duplicate Kafka offsets.
func (p *Pool) InsertReading(ctx context.Context, r reading.Reading) error {
	_, err := p.pool.Exec(
		ctx,
		insertReadingSQL,
		r.RoomID,
		r.DeviceID,
		r.SensorType,
		r.Temperature,
		r.Humidity,
		r.CO2,
		r.Light,
		r.LightLevel,
		r.ObservedAt,
		r.ReceivedAt,
		r.KafkaTopic,
		r.KafkaPartition,
		r.KafkaOffset,
	)
	if err != nil {
		return fmt.Errorf("insert environment reading: %w", err)
	}

	return nil
}
