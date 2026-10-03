package storage

import (
	"context"
	"errors"
	"fmt"
	"time"

	"occupancy-monitor/internal/event"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const upsertSensorSQL = `
INSERT INTO home_api.sensor (sensor_id, display_name)
VALUES ($1::text, left($1::text, 100))
ON CONFLICT (sensor_id) DO NOTHING;
`

const selectSensorRoomSQL = `
SELECT room_id
FROM home_api.sensor
WHERE sensor_id = $1;
`

const insertEventSQL = `
INSERT INTO occupancy.occupancy_event (
  room_id,
  sensor_id,
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

// InsertEvent persists an occupancy event in one transaction. The sensor is
// registered if unknown (display_name is never overwritten). The event
// snapshots the sensor's current room. Duplicate Kafka offsets are ignored.
func (p *Pool) InsertEvent(ctx context.Context, e event.Event) error {
	return persistEvent(ctx, pgxEventDB{pool: p.pool}, e)
}

type eventRow struct {
	RoomID    *string
	SensorID  *string
	EventType string
	Present   *bool
	Open      *bool

	ObservedAt     time.Time
	ReceivedAt     time.Time
	KafkaTopic     string
	KafkaPartition int32
	KafkaOffset    int64
}

type eventDB interface {
	Begin(context.Context) (eventTx, error)
}

type eventTx interface {
	UpsertSensor(ctx context.Context, sensorID string) error
	SensorRoomID(ctx context.Context, sensorID string) (*string, error)
	InsertEvent(ctx context.Context, row eventRow) error
	Commit(ctx context.Context) error
	Rollback(ctx context.Context) error
}

func persistEvent(ctx context.Context, db eventDB, e event.Event) (err error) {
	if e.SensorID == nil {
		return fmt.Errorf("sensor_id: required")
	}

	tx, err := db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin occupancy event transaction: %w", err)
	}
	defer func() {
		if err != nil {
			_ = tx.Rollback(context.WithoutCancel(ctx))
		}
	}()

	if err = tx.UpsertSensor(ctx, *e.SensorID); err != nil {
		return fmt.Errorf("upsert sensor: %w", err)
	}

	roomID, err := tx.SensorRoomID(ctx, *e.SensorID)
	if err != nil {
		return fmt.Errorf("read sensor room: %w", err)
	}

	row := eventRow{
		RoomID:         roomID,
		SensorID:       e.SensorID,
		EventType:      e.EventType,
		Present:        e.Present,
		Open:           e.Open,
		ObservedAt:     e.ObservedAt,
		ReceivedAt:     e.ReceivedAt,
		KafkaTopic:     e.KafkaTopic,
		KafkaPartition: e.KafkaPartition,
		KafkaOffset:    e.KafkaOffset,
	}

	if err = tx.InsertEvent(ctx, row); err != nil {
		return fmt.Errorf("insert occupancy event: %w", err)
	}

	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("commit occupancy event: %w", err)
	}

	return nil
}

type pgxEventDB struct {
	pool *pgxpool.Pool
}

func (d pgxEventDB) Begin(ctx context.Context) (eventTx, error) {
	tx, err := d.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}

	return pgxEventTx{tx: tx}, nil
}

type pgxEventTx struct {
	tx pgx.Tx
}

func (t pgxEventTx) UpsertSensor(ctx context.Context, sensorID string) error {
	_, err := t.tx.Exec(ctx, upsertSensorSQL, sensorID)
	return err
}

func (t pgxEventTx) SensorRoomID(ctx context.Context, sensorID string) (*string, error) {
	var roomID *string
	err := t.tx.QueryRow(ctx, selectSensorRoomSQL, sensorID).Scan(&roomID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return roomID, nil
}

func (t pgxEventTx) InsertEvent(ctx context.Context, row eventRow) error {
	_, err := t.tx.Exec(
		ctx,
		insertEventSQL,
		row.RoomID,
		row.SensorID,
		row.EventType,
		row.Present,
		row.Open,
		row.ObservedAt,
		row.ReceivedAt,
		row.KafkaTopic,
		row.KafkaPartition,
		row.KafkaOffset,
	)
	return err
}

func (t pgxEventTx) Commit(ctx context.Context) error {
	return t.tx.Commit(ctx)
}

func (t pgxEventTx) Rollback(ctx context.Context) error {
	return t.tx.Rollback(ctx)
}
