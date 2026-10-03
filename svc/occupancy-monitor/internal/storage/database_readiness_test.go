package storage

import (
	"context"
	"errors"
	"occupancy-monitor/internal/handler"
	"testing"
	"time"

	"go.uber.org/zap"
)

type mockPinger struct {
	ping func(context.Context) error
}

func (m *mockPinger) Ping(ctx context.Context) error {
	return m.ping(ctx)
}

func TestCheckDatabaseReadinessSetsReady(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	readiness := handler.NewReadiness()
	readiness.SetKafkaReady(true)
	pingCount := 0

	db := &mockPinger{
		ping: func(context.Context) error {
			pingCount++
			if pingCount >= 2 {
				cancel()
			}
			return nil
		},
	}

	done := make(chan struct{})
	go func() {
		checkDatabaseReadiness(ctx, db, zap.NewNop(), time.Second, time.Millisecond, readiness)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("checkDatabaseReadiness() did not stop")
	}

	if pingCount < 2 {
		t.Fatalf("Ping() called %d times, want at least 2", pingCount)
	}
}

func TestCheckDatabaseReadinessRetriesAfterError(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	readiness := handler.NewReadiness()
	readiness.SetKafkaReady(true)
	pingCount := 0

	db := &mockPinger{
		ping: func(context.Context) error {
			pingCount++
			if pingCount == 1 {
				return errors.New("database unavailable")
			}
			cancel()
			return nil
		},
	}

	checkDatabaseReadiness(ctx, db, zap.NewNop(), time.Second, time.Millisecond, readiness)

	if pingCount != 2 {
		t.Fatalf("Ping() called %d times, want 2", pingCount)
	}
}

func TestCheckDatabaseReadinessStopsWhenContextIsCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())

	readiness := handler.NewReadiness()
	readiness.SetKafkaReady(true)

	db := &mockPinger{
		ping: func(context.Context) error {
			return errors.New("database unavailable")
		},
	}

	done := make(chan struct{})
	go func() {
		checkDatabaseReadiness(ctx, db, zap.NewNop(), time.Second, time.Second, readiness)
		close(done)
	}()

	time.Sleep(10 * time.Millisecond)
	cancel()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("checkDatabaseReadiness() did not stop")
	}

	if readiness.IsReady() {
		t.Fatal("readiness = true, want false")
	}
}
