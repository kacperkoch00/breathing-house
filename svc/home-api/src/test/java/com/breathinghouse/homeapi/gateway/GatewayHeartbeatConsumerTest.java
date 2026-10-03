package com.breathinghouse.homeapi.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GatewayHeartbeatConsumerTest {

    @Mock
    private GatewayHeartbeatRepository repository;

    @Mock
    private Acknowledgment acknowledgment;

    private GatewayHeartbeatConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new GatewayHeartbeatConsumer(
                new GatewayHeartbeatParser(objectMapper),
                repository,
                Duration.ofMillis(1));
    }

    @Test
    void persistsValidEnvelopeBeforeAck() {
        consumer.consume(record(validPayload()), acknowledgment);

        ArgumentCaptor<GatewayHeartbeat> captor = ArgumentCaptor.forClass(GatewayHeartbeat.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().reportedStatus()).isEqualTo("ONLINE");
        assertThat(captor.getValue().payload()).containsEntry("uptime", 3600);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void acknowledgesNonRetryableInvalidRecordsWithoutPersist() {
        consumer.consume(record("{"), acknowledgment);

        verify(repository, never()).insert(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void doesNotAcknowledgeUntilDatabaseSucceedsAndRetriesSameRecord() {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new DataAccessResourceFailureException("db down");
            }
            return null;
        }).when(repository).insert(any());

        consumer.consume(record(validPayload()), acknowledgment);

        verify(repository, times(2)).insert(any());
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    void treatsDuplicateInsertAsSuccessAndAcknowledges() {
        // ON CONFLICT DO NOTHING is handled in the repository; consumer sees success.
        consumer.consume(record(validPayload()), acknowledgment);
        consumer.consume(record(validPayload()), acknowledgment);

        verify(repository, times(2)).insert(any());
        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenInterruptedDuringRetry() {
        doThrow(new DataAccessResourceFailureException("db down")).when(repository).insert(any());
        Thread.currentThread().interrupt();

        consumer.consume(record(validPayload()), acknowledgment);

        verify(acknowledgment, never()).acknowledge();
        assertThat(Thread.interrupted()).isTrue();
    }

    private static ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("status-data", 0, 42L, "gateway", payload);
    }

    private static String validPayload() {
        return """
                {
                  "schemaVersion": 1,
                  "roomId": "gateway",
                  "deviceId": null,
                  "type": "STATUS",
                  "observedAt": "2026-10-03T10:00:00Z",
                  "receivedAt": "2026-10-03T10:00:00Z",
                  "values": {
                    "status": "ONLINE",
                    "uptime": 3600,
                    "connected": true
                  }
                }
                """;
    }
}
