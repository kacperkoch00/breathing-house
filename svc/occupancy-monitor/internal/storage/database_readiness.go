package storage

import (
	"context"
	"time"

	"occupancy-monitor/internal/handler"

	"go.uber.org/zap"
)

type pinger interface {
	Ping(context.Context) error
}

// CheckReadiness pings the database until ctx is canceled, updating readiness.
func CheckReadiness(
	ctx context.Context,
	pool *Pool,
	logger *zap.Logger,
	timeout time.Duration,
	retryDelay time.Duration,
	readiness *handler.Readiness,
) {
	checkDatabaseReadiness(ctx, pool, logger, timeout, retryDelay, readiness)
}

func checkDatabaseReadiness(
	ctx context.Context,
	db pinger,
	logger *zap.Logger,
	timeout time.Duration,
	retryDelay time.Duration,
	readiness *handler.Readiness,
) {
	for {
		pingCtx, cancel := context.WithTimeout(ctx, timeout)
		err := db.Ping(pingCtx)
		cancel()

		if err == nil {
			readiness.SetDatabaseReady(true)
			logger.Debug("database is ready")
		} else {
			readiness.SetDatabaseReady(false)
			logger.Error("database is not ready", zap.Error(err))
		}

		select {
		case <-time.After(retryDelay):
		case <-ctx.Done():
			readiness.SetDatabaseReady(false)
			return
		}
	}
}
