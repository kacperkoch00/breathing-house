package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

// Kafka tracks thin Kafka consumer counters for Prometheus scraping.
type Kafka struct {
	messagesReceived *prometheus.CounterVec
	fetchErrors      *prometheus.CounterVec
}

// NewKafka registers kafka consumer counters on the given registerer.
func NewKafka(reg prometheus.Registerer) *Kafka {
	factory := promauto.With(reg)
	return &Kafka{
		messagesReceived: factory.NewCounterVec(prometheus.CounterOpts{
			Name: "kafka_messages_received_total",
			Help: "Total Kafka records received by the consumer",
		}, []string{"topic"}),
		fetchErrors: factory.NewCounterVec(prometheus.CounterOpts{
			Name: "kafka_fetch_errors_total",
			Help: "Total Kafka fetch errors observed by the consumer",
		}, []string{"topic"}),
	}
}

// MessageReceived increments kafka_messages_received_total for the topic.
func (k *Kafka) MessageReceived(topic string) {
	k.messagesReceived.WithLabelValues(topic).Inc()
}

// FetchError increments kafka_fetch_errors_total for the topic.
func (k *Kafka) FetchError(topic string) {
	k.fetchErrors.WithLabelValues(topic).Inc()
}
