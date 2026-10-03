package handler

import "sync/atomic"

type Readiness struct {
	kafkaReady    atomic.Bool
	databaseReady atomic.Bool
}

func NewReadiness() *Readiness {
	return &Readiness{}
}

func (r *Readiness) SetKafkaReady(ready bool) {
	r.kafkaReady.Store(ready)
}

func (r *Readiness) SetDatabaseReady(ready bool) {
	r.databaseReady.Store(ready)
}

// SetReady sets both Kafka and database readiness. Kept for tests that treat
// readiness as a single flag.
func (r *Readiness) SetReady(ready bool) {
	r.kafkaReady.Store(ready)
	r.databaseReady.Store(ready)
}

func (r *Readiness) IsReady() bool {
	return r.kafkaReady.Load() && r.databaseReady.Load()
}
