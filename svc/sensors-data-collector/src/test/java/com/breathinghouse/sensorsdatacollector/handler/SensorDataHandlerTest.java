package com.breathinghouse.sensorsdatacollector.handler;

import com.breathinghouse.sensorsdatacollector.handler.transformer.AirSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.OpeningSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.PresenceSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.RoomSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.SensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.StatusSensorDataTransformer;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SensorDataHandlerTest {

    private SensorDataHandler handler;
    private TransformedSensorDataProducer producer;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        producer = Mockito.mock(TransformedSensorDataProducer.class);

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
                "room", "{\"temperature\":22.5,\"light\":250}",
                "air", "{\"temperature\":22.5,\"humidity\":45,\"co2\":650}",
                "opening", "{\"state\": \"open\"}",
                "presence", "{\"presence\": \"detected\"}",
                "status", "{\"status\":\"ONLINE\"}");

        assertDoesNotThrow(() ->
                handler.handle(payloads.get(sensorType), "home/kitchen/" + sensorType)
        );

        verify(producer).send(any(SensorData.class));
    }

    @Test
    void shouldNotPublishInvalidPayload() {
        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/air")
        );

        verify(producer, never()).send(any());
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

        verify(producer, never()).send(any());
    }

    @Test
    void shouldIgnoreUnknownSensorType() {
        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/unknown")
        );

        verify(producer, never()).send(any());
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
                Instant now = Instant.now();
                return new SensorData(
                        SensorData.SCHEMA_VERSION,
                        roomId,
                        null,
                        SensorType.ROOM,
                        now,
                        now,
                        Map.of()
                );
            }
        };

        TransformedSensorDataProducer producerWithoutAir = Mockito.mock(TransformedSensorDataProducer.class);
        SensorDataHandler handlerWithoutAir = new SensorDataHandler(List.of(transformer), producerWithoutAir);

        assertDoesNotThrow(() ->
                handlerWithoutAir.handle("{}", "home/kitchen/air")
        );

        verify(producerWithoutAir, never()).send(any());
    }
}
