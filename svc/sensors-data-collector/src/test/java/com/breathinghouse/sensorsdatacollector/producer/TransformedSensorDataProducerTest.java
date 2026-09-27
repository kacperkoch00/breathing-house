package com.breathinghouse.sensorsdatacollector.producer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransformedSensorDataProducerTest {

    @Mock
    private KafkaTemplate<String, SensorData> kafkaTemplate;

    private TransformedSensorDataProducer producer;

    @BeforeEach
    void setUp() {
        KafkaTopicProperties topicProperties = new KafkaTopicProperties(
                "sensor-data",
                "event-data",
                "status-data"
        );

        producer = new TransformedSensorDataProducer(kafkaTemplate, topicProperties);
    }

    @ParameterizedTest
    @CsvSource({
            "ROOM, sensor-data",
            "AIR, sensor-data",
            "OPENING, event-data",
            "PRESENCE, event-data",
            "STATUS, status-data"
    })
    void shouldSendDataToCorrectTopic(SensorType type, String expectedTopic) {
        SensorData sensorData = new SensorData("kitchen", type, Instant.now(), Map.of());
        SendResult<String, SensorData> sendResult = successfulSendResult(expectedTopic);
        when(kafkaTemplate.send(anyString(), anyString(), any(SensorData.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        producer.send(sensorData);

        verify(kafkaTemplate).send(expectedTopic, "kitchen", sensorData);
    }

    @Test
    void shouldNotThrowWhenSendFutureFails() {
        SensorData sensorData = new SensorData("kitchen", SensorType.ROOM, Instant.now(), Map.of());
        when(kafkaTemplate.send(eq("sensor-data"), eq("kitchen"), eq(sensorData)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        assertDoesNotThrow(() -> producer.send(sensorData));

        verify(kafkaTemplate).send("sensor-data", "kitchen", sensorData);
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, SensorData> successfulSendResult(String topic) {
        SendResult<String, SensorData> sendResult = mock(SendResult.class);
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
