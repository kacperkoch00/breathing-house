package consumer

import (
	"context"
	"environment-monitor/internal/handler"
	"environment-monitor/internal/metrics"
	"environment-monitor/internal/reading"
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

type readingStore interface {
	InsertReading(context.Context, reading.Reading) error
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

		if !waitRetry(ctx, retryDelay) {
			readiness.SetKafkaReady(false)
			return
		}
	}
}

func PollEvents(
	ctx context.Context,
	client *kgo.Client,
	logger *zap.Logger,
	kafkaRetryDelay time.Duration,
	dbTimeout time.Duration,
	dbRetryDelay time.Duration,
	kafkaMetrics *metrics.Kafka,
	store readingStore,
	readiness *handler.Readiness,
) {
	pollEvents(
		ctx,
		&franzKafkaClient{client: client},
		logger,
		kafkaRetryDelay,
		dbTimeout,
		dbRetryDelay,
		kafkaMetrics,
		store,
		readiness,
	)
}

func pollEvents(
	ctx context.Context,
	client kafkaClient,
	logger *zap.Logger,
	kafkaRetryDelay time.Duration,
	dbTimeout time.Duration,
	dbRetryDelay time.Duration,
	kafkaMetrics *metrics.Kafka,
	store readingStore,
	readiness *handler.Readiness,
) {
	for {
		logger.Debug("polling Kafka")
		fetches := client.PollFetches(ctx)
		logger.Debug("Kafka poll returned")

		if ctx.Err() != nil {
			return
		}

		if !processFetches(ctx, client, fetches, logger, dbTimeout, dbRetryDelay, kafkaRetryDelay, kafkaMetrics, store, readiness) {
			if !waitRetry(ctx, kafkaRetryDelay) {
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
	dbRetryDelay time.Duration,
	kafkaRetryDelay time.Duration,
	kafkaMetrics *metrics.Kafka,
	store readingStore,
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

		decoded, err := reading.Decode(record.Value, record.Topic, record.Partition, record.Offset)
		if err != nil {
			logger.Warn(
				"skipping invalid Kafka record",
				zap.String("topic", record.Topic),
				zap.Int32("partition", record.Partition),
				zap.Int64("offset", record.Offset),
				zap.Error(err),
			)
			if !commitRecordWithRetry(ctx, client, record, logger, kafkaRetryDelay, "failed to commit skipped Kafka record") {
				return false
			}
			continue
		}

		if !persistReadingWithRetry(ctx, store, decoded, record, logger, dbTimeout, dbRetryDelay, readiness) {
			return false
		}

		if !commitRecordWithRetry(ctx, client, record, logger, kafkaRetryDelay, "failed to commit Kafka record after persist") {
			return false
		}

		logger.Debug(
			"persisted environment reading",
			zap.String("topic", record.Topic),
			zap.Int32("partition", record.Partition),
			zap.Int64("offset", record.Offset),
			zap.String("sensor_id", stringOrEmpty(decoded.SensorID)),
			zap.String("sensor_type", decoded.SensorType),
		)
	}

	return true
}

func stringOrEmpty(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func persistReadingWithRetry(
	ctx context.Context,
	store readingStore,
	decoded reading.Reading,
	record *kgo.Record,
	logger *zap.Logger,
	dbTimeout time.Duration,
	dbRetryDelay time.Duration,
	readiness *handler.Readiness,
) bool {
	for {
		insertCtx, cancel := context.WithTimeout(ctx, dbTimeout)
		err := store.InsertReading(insertCtx, decoded)
		cancel()
		if err == nil {
			readiness.SetDatabaseReady(true)
			return true
		}

		readiness.SetDatabaseReady(false)
		logger.Error(
			"failed to persist environment reading",
			zap.String("topic", record.Topic),
			zap.Int32("partition", record.Partition),
			zap.Int64("offset", record.Offset),
			zap.String("sensor_id", stringOrEmpty(decoded.SensorID)),
			zap.Error(err),
		)

		if !waitRetry(ctx, dbRetryDelay) {
			return false
		}
	}
}

func commitRecordWithRetry(
	ctx context.Context,
	client kafkaClient,
	record *kgo.Record,
	logger *zap.Logger,
	retryDelay time.Duration,
	errorMessage string,
) bool {
	for {
		err := client.CommitRecords(ctx, record)
		if err == nil {
			return true
		}

		logger.Error(
			errorMessage,
			zap.String("topic", record.Topic),
			zap.Int32("partition", record.Partition),
			zap.Int64("offset", record.Offset),
			zap.Error(err),
		)

		if !waitRetry(ctx, retryDelay) {
			return false
		}
	}
}

func waitRetry(ctx context.Context, delay time.Duration) bool {
	select {
	case <-time.After(delay):
		return true
	case <-ctx.Done():
		return false
	}
}
