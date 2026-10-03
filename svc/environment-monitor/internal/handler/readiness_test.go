package handler

import "testing"

func TestReadinessRequiresKafkaAndDatabase(t *testing.T) {
	r := NewReadiness()

	if r.IsReady() {
		t.Fatal("IsReady() = true, want false initially")
	}

	r.SetKafkaReady(true)
	if r.IsReady() {
		t.Fatal("IsReady() = true with only Kafka ready")
	}

	r.SetDatabaseReady(true)
	if !r.IsReady() {
		t.Fatal("IsReady() = false, want true when both ready")
	}

	r.SetDatabaseReady(false)
	if r.IsReady() {
		t.Fatal("IsReady() = true after database became unready")
	}
}
