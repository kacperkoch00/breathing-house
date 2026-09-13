package consumer

import (
	"context"
	"occupancy-monitor/internal/handler"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
)

type kafkaClient interface {
	Ping(context.Context) error
	PollFetches(context.Context) kafkaFetches
}

type kafkaFetches interface {
	EachError(func(string, int32, error))
	EachRecord(func(*kgo.Record))
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
		if err := client.Ping(ctx); err == nil {
			readiness.SetReady(true)
			logger.Debug("Kafka is ready")
			return
		} else {
			readiness.SetReady(false)
			logger.Error("Kafka is not ready", zap.Error(err))
		}

		select {
		case <-time.After(retryDelay):
		case <-ctx.Done():
			readiness.SetReady(false)
			return
		}
	}
}

func PollEvents(ctx context.Context, client *kgo.Client, logger *zap.Logger, retryDelay time.Duration) {
	pollEvents(
		ctx,
		&franzKafkaClient{client: client},
		logger,
		retryDelay,
	)
}

func pollEvents(ctx context.Context, client kafkaClient, logger *zap.Logger, retryDelay time.Duration) {
	for {
		logger.Debug("polling Kafka")
		fetches := client.PollFetches(ctx)
		logger.Debug("Kafka poll returned")

		if ctx.Err() != nil {
			return
		}

		if !processFetches(fetches, logger) {
			select {
			case <-time.After(retryDelay):
				continue
			case <-ctx.Done():
				return
			}
		}
	}
}

func processFetches(fetches kafkaFetches, logger *zap.Logger) bool {
	hasError := false

	fetches.EachError(func(topic string, partition int32, err error) {
		hasError = true
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

	fetches.EachRecord(func(record *kgo.Record) {
		logger.Debug(
			"Kafka event received",
			zap.String("topic", record.Topic),
			zap.ByteString("value", record.Value),
		)
	})

	return true
}
