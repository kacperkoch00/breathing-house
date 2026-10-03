package storage

import (
	"context"
	"fmt"

	"occupancy-monitor/internal/event"

	"github.com/jackc/pgx/v5/pgxpool"
)

const insertEventSQL = `
INSERT INTO occupancy.occupancy_event (
  room_id,
  device_id,
  event_type,
  present,
  open,
  observed_at,
  received_at,
  kafka_topic,
  kafka_partition,
  kafka_offset
)
VALUES (
  $1, $2, $3, $4, $5, $6, $7, $8, $9, $10
)
ON CONFLICT (kafka_topic, kafka_partition, kafka_offset)
DO NOTHING;
`

// Pool wraps a pgx connection pool for occupancy events.
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

// InsertEvent appends an occupancy event, ignoring duplicate Kafka offsets.
func (p *Pool) InsertEvent(ctx context.Context, e event.Event) error {
	_, err := p.pool.Exec(
		ctx,
		insertEventSQL,
		e.RoomID,
		e.DeviceID,
		e.EventType,
		e.Present,
		e.Open,
		e.ObservedAt,
		e.ReceivedAt,
		e.KafkaTopic,
		e.KafkaPartition,
		e.KafkaOffset,
	)
	if err != nil {
		return fmt.Errorf("insert occupancy event: %w", err)
	}

	return nil
}
