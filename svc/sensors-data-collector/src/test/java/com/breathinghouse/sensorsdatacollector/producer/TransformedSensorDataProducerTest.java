package com.breathinghouse.sensorsdatacollector.producer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.verify;

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

        producer.send(sensorData);

        verify(kafkaTemplate).send(expectedTopic, "kitchen", sensorData);
    }
}