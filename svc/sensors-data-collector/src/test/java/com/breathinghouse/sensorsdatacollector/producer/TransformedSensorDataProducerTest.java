package com.breathinghouse.sensorsdatacollector.producer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.breathinghouse.sensorsdatacollector.metrics.SensorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private SimpleMeterRegistry meterRegistry;
    private TransformedSensorDataProducer producer;

    @BeforeEach
    void setUp() {
        KafkaTopicProperties topicProperties = new KafkaTopicProperties(
                "sensor-data",
                "event-data",
                "status-data",
                "sensor-data-dlq"
        );
        meterRegistry = new SimpleMeterRegistry();
        producer = new TransformedSensorDataProducer(
                kafkaTemplate,
                topicProperties,
                new SensorMetrics(meterRegistry)
        );
    }

    @ParameterizedTest
    @CsvSource({
            "ROOM, sensor-data",
            "AIR, sensor-data",
            "OPENING, event-data",
            "PRESENCE, event-data",
            "STATUS, status-data"
    })
    void shouldSendDataToCorrectTopicKeyedBySensorIdOrGatewayForStatus(SensorType type, String expectedTopic) {
        Instant now = Instant.now();
        SensorData sensorData = sensorDataFor(type, now);
        String expectedKey = type == SensorType.STATUS ? "gateway" : "sensor-1";
        SendResult<String, SensorData> sendResult = successfulSendResult(expectedTopic);
        when(kafkaTemplate.send(anyString(), anyString(), any(SensorData.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        producer.send(sensorData);

        verify(kafkaTemplate).send(expectedTopic, expectedKey, sensorData);
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.PUBLISHED, "type", type.name()).count());
        assertEquals(0.0, meterRegistry.counter(SensorMetrics.PUBLISH_FAILED, "kind", "sensor").count());
    }

    @Test
    void shouldNotThrowWhenSendFutureFails() {
        Instant now = Instant.now();
        SensorData sensorData = sensorDataFor(SensorType.ROOM, now);
        when(kafkaTemplate.send(eq("sensor-data"), eq("sensor-1"), eq(sensorData)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        assertDoesNotThrow(() -> producer.send(sensorData));

        verify(kafkaTemplate).send("sensor-data", "sensor-1", sensorData);
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.PUBLISH_FAILED, "kind", "sensor").count());
        assertEquals(0.0, meterRegistry.counter(SensorMetrics.PUBLISHED, "type", "ROOM").count());
    }

    private static SensorData sensorDataFor(SensorType type, Instant now) {
        if (type == SensorType.STATUS) {
            return new SensorData(SensorData.STATUS_SCHEMA_VERSION, null, "gateway", null, type, now, now, Map.of());
        }
        return new SensorData(SensorData.SCHEMA_VERSION, "sensor-1", null, null, type, now, now, Map.of());
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
