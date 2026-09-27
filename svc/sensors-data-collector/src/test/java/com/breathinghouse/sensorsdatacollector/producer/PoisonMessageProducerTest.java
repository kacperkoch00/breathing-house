package com.breathinghouse.sensorsdatacollector.producer;

import com.breathinghouse.sensorsdatacollector.metrics.SensorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PoisonMessageProducerTest {

    @Mock
    private KafkaTemplate<String, PoisonMessage> kafkaTemplate;

    private SimpleMeterRegistry meterRegistry;
    private PoisonMessageProducer producer;

    @BeforeEach
    void setUp() {
        KafkaTopicProperties topicProperties = new KafkaTopicProperties(
                "sensor-data",
                "event-data",
                "status-data",
                "sensor-data-dlq"
        );
        meterRegistry = new SimpleMeterRegistry();
        producer = new PoisonMessageProducer(
                kafkaTemplate,
                topicProperties,
                new SensorMetrics(meterRegistry)
        );
    }

    @Test
    void shouldSendPoisonMessageToDlqTopicWithRoomIdKey() {
        PoisonMessage poisonMessage = new PoisonMessage(
                Instant.now(),
                "home/kitchen/air",
                "kitchen",
                "AIR",
                "Missing required field: temperature",
                "{}"
        );
        SendResult<String, PoisonMessage> sendResult = successfulSendResult("sensor-data-dlq");
        when(kafkaTemplate.send(anyString(), anyString(), any(PoisonMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        producer.send(poisonMessage);

        verify(kafkaTemplate).send("sensor-data-dlq", "kitchen", poisonMessage);
        assertEquals(0.0, meterRegistry.counter(SensorMetrics.PUBLISH_FAILED, "kind", "dlq").count());
    }

    @Test
    void shouldNotThrowWhenSendFutureFails() {
        PoisonMessage poisonMessage = new PoisonMessage(
                Instant.now(),
                "home/kitchen/air",
                "kitchen",
                "AIR",
                "Missing required field: temperature",
                "{}"
        );
        when(kafkaTemplate.send(eq("sensor-data-dlq"), eq("kitchen"), eq(poisonMessage)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        assertDoesNotThrow(() -> producer.send(poisonMessage));

        verify(kafkaTemplate).send("sensor-data-dlq", "kitchen", poisonMessage);
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.PUBLISH_FAILED, "kind", "dlq").count());
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, PoisonMessage> successfulSendResult(String topic) {
        SendResult<String, PoisonMessage> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(topic, 0),
                0L,
                0,
                System.currentTimeMillis(),
                0,
                0
        );
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        return sendResult;
    }
}
