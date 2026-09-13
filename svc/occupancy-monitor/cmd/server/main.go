package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"occupancy-monitor/internal/api"
	"occupancy-monitor/internal/handler"
	"occupancy-monitor/internal/kafka/consumer"

	"github.com/kelseyhightower/envconfig"
	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type Config struct {
	HTTPPort             string        `envconfig:"HTTP_PORT" default:"8081"`
	ShutdownTimeout      time.Duration `envconfig:"SHUTDOWN_TIMEOUT" default:"10s"`
	LogLevel             string        `envconfig:"LOG_LEVEL" default:"info"`
	KafkaBrokers         []string      `envconfig:"KAFKA_BROKERS" default:"localhost:9092"`
	KafkaConsumerTopic   string        `envconfig:"KAFKA_CONSUMER_TOPIC" default:"event-data"`
	KafkaConsumerGroupID string        `envconfig:"KAFKA_CONSUMER_GROUP_ID" default:"occupancy-monitor"`
	KafkaRetryDelay      time.Duration `envconfig:"KAFKA_RETRY_DELAY" default:"5s"`
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "server failed: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	config, logger, err := initialize()
	if err != nil {
		return err
	}
	defer func() { _ = logger.Sync() }()

	kafkaConsumer, err := initializeKafkaConsumer(config, logger)
	if err != nil {
		return err
	}
	defer kafkaConsumer.Close()

	readiness := handler.NewReadiness()
	server := newHTTPServer(config, readiness)
	serverErrors := startHTTPServer(server, config, logger)

	signalCtx, stopSignals := createSignalContext()
	defer stopSignals()

	startConsuming(signalCtx, kafkaConsumer, logger, config.KafkaRetryDelay, readiness)

	return waitForShutdown(signalCtx, serverErrors, server, config, logger)
}

func startConsuming(ctx context.Context, kafkaConsumer *kgo.Client, logger *zap.Logger, retryDelay time.Duration, readiness *handler.Readiness) {
	go consumer.PollEvents(ctx, kafkaConsumer, logger, retryDelay, readiness)
}

func initialize() (Config, *zap.Logger, error) {
	config, err := loadConfig()
	if err != nil {
		return Config{}, nil, fmt.Errorf("load config: %w", err)
	}

	logger, err := loadLogger(config)
	if err != nil {
		return Config{}, nil, fmt.Errorf("initialize logger: %w", err)
	}

	return config, logger, nil
}

func initializeKafkaConsumer(config Config, logger *zap.Logger) (*kgo.Client, error) {
	kafkaConsumer, err := consumer.NewKafkaConsumer(config.KafkaBrokers, config.KafkaConsumerTopic, config.KafkaConsumerGroupID, logger)
	if err != nil {
		return nil, fmt.Errorf("initialize Kafka consumer: %w", err)
	}

	return kafkaConsumer, nil
}

func startHTTPServer(server *http.Server, config Config, logger *zap.Logger) <-chan error {
	serverErrors := make(chan error, 1)

	go func() {
		logger.Info("starting HTTP server", zap.String("address", server.Addr), zap.Duration("shutdown_timeout", config.ShutdownTimeout))
		serverErrors <- server.ListenAndServe()
	}()

	return serverErrors
}

func createSignalContext() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
}

func waitForShutdown(signalCtx context.Context, serverErrors <-chan error, server *http.Server, config Config, logger *zap.Logger) error {
	select {
	case err := <-serverErrors:
		if err != nil && err != http.ErrServerClosed {
			return fmt.Errorf("HTTP server failed: %w", err)
		}

	case <-signalCtx.Done():
		logger.Info("shutdown signal received")

		if err := gracefulShutdown(server, config, logger); err != nil {
			return err
		}
	}

	return nil
}

func loadConfig() (Config, error) {
	var config Config

	if err := envconfig.Process("occupancy-monitor", &config); err != nil {
		return Config{}, err
	}

	return config, nil
}

func loadLogger(config Config) (*zap.Logger, error) {
	var zapConfig zap.Config

	if strings.EqualFold(config.LogLevel, "debug") {
		zapConfig = zap.NewDevelopmentConfig()
	} else {
		zapConfig = zap.NewProductionConfig()
	}

	var level zapcore.Level

	if err := level.UnmarshalText([]byte(strings.ToLower(config.LogLevel))); err != nil {
		return nil, fmt.Errorf(
			"invalid log level %q: %w",
			config.LogLevel,
			err,
		)
	}

	zapConfig.Level = zap.NewAtomicLevelAt(level)

	return zapConfig.Build()
}

func newHTTPServer(config Config, readiness *handler.Readiness) *http.Server {
	health := handler.NewHealth(readiness)
	mux := http.NewServeMux()

	api.HandlerFromMux(health, mux)

	return &http.Server{
		Addr:              ":" + config.HTTPPort,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
}

func gracefulShutdown(server *http.Server, config Config, logger *zap.Logger) error {
	shutdownCtx, cancel := context.WithTimeout(
		context.Background(),
		config.ShutdownTimeout,
	)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", zap.Error(err))
		return fmt.Errorf("graceful shutdown failed: %w", err)
	}

	logger.Info("HTTP server stopped")

	return nil
}
