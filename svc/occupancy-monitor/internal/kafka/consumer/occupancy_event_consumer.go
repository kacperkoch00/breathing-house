package consumer

import (
	"context"
	"occupancy-monitor/internal/event"
	"occupancy-monitor/internal/handler"
	"occupancy-monitor/internal/metrics"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
)

type kafkaClient interface {
	Ping(context.Context) error
	PollFetches(context.Context) kafkaFetches
	CommitRecords(context.Context, ...*kgo.Record) error
}

type kafkaFetches interface {
	EachError(func(string, int32, error))
	EachRecord(func(*kgo.Record))
}

type eventStore interface {
	InsertEvent(context.Context, event.Event) error
}

type franzKafkaClient struct {
	client *kgo.Client
}

func (c *franzKafkaClient) Ping(ctx context.Context) error {
	return c.client.Ping(ctx)
}

func (c *franzKafkaClient) PollFetches(ctx context.Context) kafkaFetches {
	return c.client.PollFetches(ctx)
}

func (c *franzKafkaClient) CommitRecords(ctx context.Context, records ...*kgo.Record) error {
	return c.client.CommitRecords(ctx, records...)
}

func NewKafkaConsumer(brokers []string, topic string, groupID string, logger *zap.Logger) (*kgo.Client, error) {
	logger.Debug(
		"creating Kafka consumer",
		zap.Strings("brokers", brokers),
		zap.String("topic", topic),
		zap.String("groupID", groupID),
	)

	return kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumeTopics(topic),
		kgo.ConsumerGroup(groupID),
		kgo.DisableAutoCommit(),
	)
}

func CheckReadiness(ctx context.Context, client *kgo.Client, logger *zap.Logger, retryDelay time.Duration, readiness *handler.Readiness) {
	checkKafkaReadiness(
		ctx,
		&franzKafkaClient{client: client},
		logger,
		retryDelay,
		readiness,
	)
}

func checkKafkaReadiness(ctx context.Context, client kafkaClient, logger *zap.Logger, retryDelay time.Duration, readiness *handler.Readiness) {
	for {
		err := client.Ping(ctx)
		if err == nil {
			readiness.SetKafkaReady(true)
			logger.Debug("Kafka is ready")
			return
		}

		readiness.SetKafkaReady(false)
		logger.Error("Kafka is not ready", zap.Error(err))

		select {
		case <-time.After(retryDelay):
		case <-ctx.Done():
			readiness.SetKafkaReady(false)
			return
		}
	}
}

func PollEvents(
	ctx context.Context,
	client *kgo.Client,
	logger *zap.Logger,
	retryDelay time.Duration,
	dbTimeout time.Duration,
	kafkaMetrics *metrics.Kafka,
	store eventStore,
	readiness *handler.Readiness,
) {
	pollEvents(
		ctx,
		&franzKafkaClient{client: client},
		logger,
		retryDelay,
		dbTimeout,
		kafkaMetrics,
		store,
		readiness,
	)
}

func pollEvents(
	ctx context.Context,
	client kafkaClient,
	logger *zap.Logger,
	retryDelay time.Duration,
	dbTimeout time.Duration,
	kafkaMetrics *metrics.Kafka,
	store eventStore,
	readiness *handler.Readiness,
) {
	for {
		logger.Debug("polling Kafka")
		fetches := client.PollFetches(ctx)
		logger.Debug("Kafka poll returned")

		if ctx.Err() != nil {
			return
		}

		if !processFetches(ctx, client, fetches, logger, dbTimeout, kafkaMetrics, store, readiness) {
			select {
			case <-time.After(retryDelay):
				continue
			case <-ctx.Done():
				return
			}
		}
	}
}

func processFetches(
	ctx context.Context,
	client kafkaClient,
	fetches kafkaFetches,
	logger *zap.Logger,
	dbTimeout time.Duration,
	kafkaMetrics *metrics.Kafka,
	store eventStore,
	readiness *handler.Readiness,
) bool {
	hasError := false

	fetches.EachError(func(topic string, partition int32, err error) {
		hasError = true
		kafkaMetrics.FetchError(topic)
		logger.Error(
			"Kafka fetch failed",
			zap.String("topic", topic),
			zap.Int32("partition", partition),
			zap.Error(err),
		)
	})

	if hasError {
		return false
	}

	var records []*kgo.Record
	fetches.EachRecord(func(record *kgo.Record) {
		records = append(records, record)
	})

	for _, record := range records {
		kafkaMetrics.MessageReceived(record.Topic)

		decoded, err := event.Decode(record.Value, record.Topic, record.Partition, record.Offset)
		if err != nil {
			logger.Warn(
				"skipping invalid Kafka record",
				zap.String("topic", record.Topic),
				zap.Int32("partition", record.Partition),
				zap.Int64("offset", record.Offset),
				zap.Error(err),
			)
			if err := client.CommitRecords(ctx, record); err != nil {
				logger.Error("failed to commit skipped Kafka record", zap.Error(err))
				return false
			}
			continue
		}

		insertCtx, cancel := context.WithTimeout(ctx, dbTimeout)
		err = store.InsertEvent(insertCtx, decoded)
		cancel()
		if err != nil {
			readiness.SetDatabaseReady(false)
			logger.Error(
				"failed to persist occupancy event",
				zap.String("topic", record.Topic),
				zap.Int32("partition", record.Partition),
				zap.Int64("offset", record.Offset),
				zap.Error(err),
			)
			return false
		}

		readiness.SetDatabaseReady(true)

		if err := client.CommitRecords(ctx, record); err != nil {
			logger.Error(
				"failed to commit Kafka record after persist",
				zap.String("topic", record.Topic),
				zap.Int32("partition", record.Partition),
				zap.Int64("offset", record.Offset),
				zap.Error(err),
			)
			return false
		}

		logger.Debug(
			"persisted occupancy event",
			zap.String("topic", record.Topic),
			zap.Int32("partition", record.Partition),
			zap.Int64("offset", record.Offset),
			zap.String("room_id", decoded.RoomID),
			zap.String("event_type", decoded.EventType),
		)
	}

	return true
}
