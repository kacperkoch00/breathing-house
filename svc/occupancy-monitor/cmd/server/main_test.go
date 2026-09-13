package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"occupancy-monitor/internal/handler"
	"os"
	"syscall"
	"testing"
	"time"

	"go.uber.org/zap"
)

func TestNewHTTPServerHealthRoutes(t *testing.T) {
	readiness := handler.NewReadiness()
	readiness.SetReady(true)

	server := newHTTPServer(Config{HTTPPort: "8081"}, readiness)

	for _, path := range []string{"/live", "/ready"} {
		t.Run(path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, path, nil)

			server.Handler.ServeHTTP(recorder, request)

			if recorder.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusOK)
			}
		})
	}
}

func TestNewHTTPServer(t *testing.T) {
	readiness := handler.NewReadiness()
	readiness.SetReady(true)
	server := newHTTPServer(Config{HTTPPort: "8081"}, readiness)

	if server.Addr != ":8081" {
		t.Fatalf("Addr = %q, want %q", server.Addr, ":8081")
	}

	if server.Handler == nil {
		t.Fatal("Handler = nil, want non-nil")
	}

	if server.ReadHeaderTimeout != 5*time.Second {
		t.Fatalf(
			"ReadHeaderTimeout = %v, want %v",
			server.ReadHeaderTimeout,
			5*time.Second,
		)
	}
}

func TestLoadLogger(t *testing.T) {
	tests := []struct {
		name     string
		logLevel string
	}{
		{
			name:     "debug",
			logLevel: "debug",
		},
		{
			name:     "info",
			logLevel: "info",
		},
		{
			name:     "warn",
			logLevel: "warn",
		},
		{
			name:     "error",
			logLevel: "error",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			logger, err := loadLogger(Config{LogLevel: tt.logLevel})
			if err != nil {
				t.Fatalf("loadLogger() error = %v", err)
			}

			if logger == nil {
				t.Fatal("loadLogger() returned nil logger")
			}

			_ = logger.Sync()
		})
	}
}

func TestLoadLoggerInvalidLevel(t *testing.T) {
	_, err := loadLogger(Config{LogLevel: "invalid"})

	if err == nil {
		t.Fatal("loadLogger() error = nil, want error")
	}
}

func TestLoadConfig(t *testing.T) {
	t.Setenv("HTTP_PORT", "9090")
	t.Setenv("SHUTDOWN_TIMEOUT", "20s")
	t.Setenv("LOG_LEVEL", "debug")
	t.Setenv("KAFKA_BROKERS", "localhost:9092,localhost:9093")
	t.Setenv("KAFKA_CONSUMER_TOPIC", "test-events")
	t.Setenv("KAFKA_CONSUMER_GROUP_ID", "test-group")
	t.Setenv("KAFKA_RETRY_DELAY", "2s")

	config, err := loadConfig()
	if err != nil {
		t.Fatalf("loadConfig() error = %v", err)
	}

	if config.HTTPPort != "9090" {
		t.Fatalf("HTTPPort = %q, want %q", config.HTTPPort, "9090")
	}

	if config.ShutdownTimeout != 20*time.Second {
		t.Fatalf("ShutdownTimeout = %v, want %v", config.ShutdownTimeout, 20*time.Second)
	}

	if config.LogLevel != "debug" {
		t.Fatalf("LogLevel = %q, want %q", config.LogLevel, "debug")
	}

	if len(config.KafkaBrokers) != 2 {
		t.Fatalf("KafkaBrokers length = %d, want 2", len(config.KafkaBrokers))
	}

	if config.KafkaConsumerTopic != "test-events" {
		t.Fatalf(
			"KafkaConsumerTopic = %q, want %q",
			config.KafkaConsumerTopic,
			"test-events",
		)
	}

	if config.KafkaConsumerGroupID != "test-group" {
		t.Fatalf(
			"KafkaConsumerGroupID = %q, want %q",
			config.KafkaConsumerGroupID,
			"test-group",
		)
	}

	if config.KafkaRetryDelay != 2*time.Second {
		t.Fatalf(
			"KafkaRetryDelay = %v, want %v",
			config.KafkaRetryDelay,
			2*time.Second,
		)
	}
}

func TestLoadConfigInvalidValue(t *testing.T) {
	t.Setenv("SHUTDOWN_TIMEOUT", "invalid")

	_, err := loadConfig()

	if err == nil {
		t.Fatal("loadConfig() error = nil, want error")
	}
}

func TestCreateSignalContext(t *testing.T) {
	ctx, cancel := createSignalContext()
	defer cancel()

	if ctx == nil {
		t.Fatal("createSignalContext() returned nil context")
	}

	if ctx.Err() != nil {
		t.Fatalf("context error = %v, want nil", ctx.Err())
	}
}

func TestSignalContextReceivesSignal(t *testing.T) {
	ctx, cancel := createSignalContext()
	defer cancel()

	process, err := os.FindProcess(os.Getpid())
	if err != nil {
		t.Fatalf("os.FindProcess() error = %v", err)
	}

	if err := process.Signal(syscall.SIGTERM); err != nil {
		t.Fatalf("process.Signal() error = %v", err)
	}

	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("context was not canceled after SIGTERM")
	}
}

func TestWaitForShutdownServerError(t *testing.T) {
	server := &http.Server{}

	serverErrors := make(chan error, 1)
	serverErrors <- context.DeadlineExceeded

	logger := zap.NewNop()

	err := waitForShutdown(
		context.Background(),
		serverErrors,
		server,
		Config{ShutdownTimeout: time.Second},
		logger,
	)

	if err == nil {
		t.Fatal("waitForShutdown() error = nil, want error")
	}
}

func TestWaitForShutdownServerClosed(t *testing.T) {
	server := &http.Server{}

	serverErrors := make(chan error, 1)
	serverErrors <- http.ErrServerClosed

	logger := zap.NewNop()

	err := waitForShutdown(
		context.Background(),
		serverErrors,
		server,
		Config{ShutdownTimeout: time.Second},
		logger,
	)

	if err != nil {
		t.Fatalf("waitForShutdown() error = %v, want nil", err)
	}
}

func TestWaitForShutdownSignal(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	server := &http.Server{}

	serverErrors := make(chan error, 1)
	logger := zap.NewNop()

	err := waitForShutdown(
		ctx,
		serverErrors,
		server,
		Config{ShutdownTimeout: time.Second},
		logger,
	)

	if err != nil {
		t.Fatalf("waitForShutdown() error = %v, want nil", err)
	}
}

func TestGracefulShutdown(t *testing.T) {
	server := &http.Server{
		Addr: ":0",
	}

	logger := zap.NewNop()

	err := gracefulShutdown(
		server,
		Config{ShutdownTimeout: time.Second},
		logger,
	)

	if err != nil {
		t.Fatalf("gracefulShutdown() error = %v, want nil", err)
	}
}

func TestStartHTTPServer(t *testing.T) {
	server := &http.Server{
		Addr: ":0",
	}

	logger := zap.NewNop()

	serverErrors := startHTTPServer(
		server,
		Config{ShutdownTimeout: time.Second},
		logger,
	)

	if err := server.Close(); err != nil {
		t.Fatalf("server.Close() error = %v", err)
	}

	select {
	case err := <-serverErrors:
		if err != http.ErrServerClosed {
			t.Fatalf(
				"server error = %v, want %v",
				err,
				http.ErrServerClosed,
			)
		}
	case <-time.After(time.Second):
		t.Fatal("server did not stop")
	}
}

func TestInitialize(t *testing.T) {
	t.Setenv("HTTP_PORT", "8081")
	t.Setenv("LOG_LEVEL", "debug")

	config, logger, err := initialize()
	if err != nil {
		t.Fatalf("initialize() error = %v", err)
	}

	if logger == nil {
		t.Fatal("initialize() returned nil logger")
	}

	if config.HTTPPort != "8081" {
		t.Fatalf("HTTPPort = %q, want %q", config.HTTPPort, "8081")
	}

	if config.LogLevel != "debug" {
		t.Fatalf("LogLevel = %q, want %q", config.LogLevel, "debug")
	}

	_ = logger.Sync()
}

func TestInitializeKafkaConsumer(t *testing.T) {
	logger := zap.NewNop()

	config := Config{
		KafkaBrokers:         []string{"localhost:9092"},
		KafkaConsumerTopic:   "event-data",
		KafkaConsumerGroupID: "occupancy-monitor",
	}

	client, err := initializeKafkaConsumer(config, logger)
	if err != nil {
		t.Fatalf("initializeKafkaConsumer() error = %v", err)
	}

	if client == nil {
		t.Fatal("initializeKafkaConsumer() returned nil client")
	}

	client.Close()
}
