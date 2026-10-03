package storage

import (
	"context"
	"fmt"

	"environment-monitor/internal/reading"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

const upsertSensorSQL = `
INSERT INTO home_api.sensor (sensor_id, display_name)
VALUES ($1, left($1, 100))
ON CONFLICT (sensor_id) DO NOTHING;
`

const selectSensorRoomSQL = `
SELECT room_id FROM home_api.sensor WHERE sensor_id = $1;
`

const insertReadingSQL = `
INSERT INTO environment.environment_reading (
  room_id,
  sensor_id,
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

// executor is the subset of pgx.Tx used to persist a reading.
type executor interface {
	Exec(ctx context.Context, sql string, arguments ...any) (pgconn.CommandTag, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

type txRunner func(ctx context.Context, fn func(executor) error) error

// Pool wraps a pgx connection pool for environment readings.
type Pool struct {
	pool *pgxpool.Pool
	inTx txRunner
}

// NewPool creates a PostgreSQL pool from databaseURL. Does not require the
// database to be reachable; connection happens on first use.
func NewPool(ctx context.Context, databaseURL string) (*Pool, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("create postgres pool: %w", err)
	}

	return &Pool{
		pool: pool,
		inTx: func(ctx context.Context, fn func(executor) error) error {
			return pgx.BeginFunc(ctx, pool, func(tx pgx.Tx) error {
				return fn(tx)
			})
		},
	}, nil
}

// Close closes the underlying pool.
func (p *Pool) Close() {
	p.pool.Close()
}

// Ping checks database connectivity.
func (p *Pool) Ping(ctx context.Context) error {
	return p.pool.Ping(ctx)
}

// InsertReading persists a reading in a single transaction. The sensor is
// registered first (an existing sensor, including its edited display name, is
// never modified). The reading snapshots the sensor's current room. Duplicate
// Kafka offsets are ignored.
func (p *Pool) InsertReading(ctx context.Context, r reading.Reading) error {
	err := p.inTx(ctx, func(ex executor) error {
		return persistReading(ctx, ex, r)
	})
	if err != nil {
		return fmt.Errorf("insert environment reading: %w", err)
	}

	return nil
}

func persistReading(ctx context.Context, ex executor, r reading.Reading) error {
	if r.SensorID == nil {
		return fmt.Errorf("sensor_id: required")
	}

	if _, err := ex.Exec(ctx, upsertSensorSQL, *r.SensorID); err != nil {
		return fmt.Errorf("upsert sensor: %w", err)
	}

	var roomID *string
	if err := ex.QueryRow(ctx, selectSensorRoomSQL, *r.SensorID).Scan(&roomID); err != nil {
		return fmt.Errorf("select sensor room: %w", err)
	}

	_, err := ex.Exec(
		ctx,
		insertReadingSQL,
		roomID,
		r.SensorID,
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
		return fmt.Errorf("insert reading: %w", err)
	}

	return nil
}
