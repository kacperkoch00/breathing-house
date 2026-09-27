package metrics

import (
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestKafkaMessageReceived(t *testing.T) {
	reg := prometheus.NewRegistry()
	m := NewKafka(reg)

	m.MessageReceived("event-data")
	m.MessageReceived("event-data")

	if got := testutil.ToFloat64(m.messagesReceived.WithLabelValues("event-data")); got != 2 {
		t.Fatalf("kafka_messages_received_total = %v, want 2", got)
	}
}

func TestKafkaFetchError(t *testing.T) {
	reg := prometheus.NewRegistry()
	m := NewKafka(reg)

	m.FetchError("event-data")

	if got := testutil.ToFloat64(m.fetchErrors.WithLabelValues("event-data")); got != 1 {
		t.Fatalf("kafka_fetch_errors_total = %v, want 1", got)
	}
}
