package com.breathinghouse.sensorsdatacollector.handler;

import com.breathinghouse.sensorsdatacollector.handler.transformer.AirSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.OpeningSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.PresenceSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.RoomSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.SensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.StatusSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.producer.KafkaProducerConfig;
import com.breathinghouse.sensorsdatacollector.producer.TransformedSensorDataProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SensorDataHandlerTest {

    private SensorDataHandler handler;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        TransformedSensorDataProducer producer = Mockito.mock(TransformedSensorDataProducer.class);

        List<SensorDataTransformer> transformers = List.of(
                new RoomSensorDataTransformer(mapper),
                new AirSensorDataTransformer(mapper),
                new OpeningSensorDataTransformer(mapper),
                new PresenceSensorDataTransformer(mapper),
                new StatusSensorDataTransformer(mapper)
        );

        handler = new SensorDataHandler(transformers, producer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "room",
            "air",
            "opening",
            "presence",
            "status"
    })
    void shouldHandleAllSensorTypes(String sensorType) {
        Map<String, String> payloads = Map.of(
                "room", "{}",
                "air", "{}",
                "opening", "{\"state\": \"open\"}",
                "presence", "{\"presence\": \"detected\"}",
                "status", "{}");

        assertDoesNotThrow(() ->
                handler.handle(payloads.get(sensorType), "home/kitchen/" + sensorType)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "invalid",
            "home",
            "home/kitchen",
            "sensors/kitchen/air",
            "home/kitchen/air/invalid"
    })
    void shouldIgnoreInvalidTopics(String topic) {
        assertDoesNotThrow(() ->
                handler.handle("{}", topic)
        );
    }

    @Test
    void shouldIgnoreUnknownSensorType() {
        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/unknown")
        );
    }

    @Test
    void shouldIgnoreSensorTypeWithoutTransformer() {
        SensorDataTransformer transformer = new SensorDataTransformer() {
            @Override
            public SensorType supportedType() {
                return SensorType.ROOM;
            }

            @Override
            public SensorData transform(String payload, String roomId) {
                return new SensorData(
                        roomId,
                        SensorType.ROOM,
                        Instant.now(),
                        Map.of()
                );
            }
        };

        TransformedSensorDataProducer producer = Mockito.mock(TransformedSensorDataProducer.class);
        SensorDataHandler handler = new SensorDataHandler(List.of(transformer), producer);

        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/air")
        );
    }
}