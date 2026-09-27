package com.breathinghouse.sensorsdatacollector.producer;

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

    private PoisonMessageProducer producer;

    @BeforeEach
    void setUp() {
        KafkaTopicProperties topicProperties = new KafkaTopicProperties(
                "sensor-data",
                "event-data",
                "status-data",
                "sensor-data-dlq"
        );

        producer = new PoisonMessageProducer(kafkaTemplate, topicProperties);
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
