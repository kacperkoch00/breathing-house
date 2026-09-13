package consumer

import (
	"context"
	"occupancy-monitor/internal/handler"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
)

type kafkaClient interface {
	PollFetches(context.Context) kafkaFetches
}

type kafkaFetches interface {
	EachError(func(string, int32, error))
	EachRecord(func(*kgo.Record))
}

type franzKafkaClient struct {
	client *kgo.Client
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

func PollEvents(ctx context.Context, client *kgo.Client, logger *zap.Logger, retryDelay time.Duration, readiness *handler.Readiness) {
	pollEvents(ctx, &franzKafkaClient{client: client}, logger, retryDelay, readiness)
}

func pollEvents(ctx context.Context, client kafkaClient, logger *zap.Logger, retryDelay time.Duration, readiness *handler.Readiness) {
	for {
		logger.Debug("polling Kafka")
		fetches := client.PollFetches(ctx)
		logger.Debug("Kafka poll returned")

		if ctx.Err() != nil {
			readiness.SetReady(false)
			return
		}

		if !processFetches(fetches, logger) {
			readiness.SetReady(false)

			select {
			case <-time.After(retryDelay):
				continue
			case <-ctx.Done():
				readiness.SetReady(false)
				return
			}
		}

		readiness.SetReady(true)
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
