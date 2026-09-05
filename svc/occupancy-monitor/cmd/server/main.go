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

	"github.com/kelseyhightower/envconfig"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type Config struct {
	HTTPPort        string        `envconfig:"HTTP_PORT" default:"8081"`
	ShutdownTimeout time.Duration `envconfig:"SHUTDOWN_TIMEOUT" default:"10s"`
	LogLevel        string        `envconfig:"LOG_LEVEL" default:"info"`
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "server failed: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	config, err := loadConfig()
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}

	logger, err := loadLogger(config)
	if err != nil {
		return fmt.Errorf("initialize logger: %w", err)
	}
	defer func() { _ = logger.Sync() }()

	server := newHTTPServer(config)
	serverErrors := make(chan error, 1)

	go func() {
		logger.Info("starting HTTP server",
			zap.String("address", server.Addr),
			zap.Duration("shutdown_timeout", config.ShutdownTimeout),
		)
		serverErrors <- server.ListenAndServe()
	}()

	signalCtx, stopSignals := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stopSignals()

	select {
	case err := <-serverErrors:
		if err != nil && err != http.ErrServerClosed {
			return fmt.Errorf("HTTP server failed: %w", err)
		}
	case <-signalCtx.Done():
		logger.Info("shutdown signal received")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), config.ShutdownTimeout)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Error("graceful shutdown failed", zap.Error(err))
			return fmt.Errorf("graceful shutdown failed: %w", err)
		}
		logger.Info("HTTP server stopped")
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
		return nil, fmt.Errorf("invalid log level %q: %w", config.LogLevel, err)
	}
	zapConfig.Level = zap.NewAtomicLevelAt(level)

	return zapConfig.Build()
}

func newHTTPServer(config Config) *http.Server {
	health := handler.NewHealth()
	mux := http.NewServeMux()
	api.HandlerFromMux(health, mux)

	return &http.Server{
		Addr:              ":" + config.HTTPPort,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
}
