package com.breathinghouse.sensorsdatacollector.handler;

import com.breathinghouse.sensorsdatacollector.handler.transformer.AirSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.OpeningSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.PresenceSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.RoomSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.SensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.StatusSensorDataTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SensorDataHandlerTest {

    private SensorDataHandler handler;

    @BeforeEach
    void setUp() {
        List<SensorDataTransformer> transformers = List.of(
                new RoomSensorDataTransformer(),
                new AirSensorDataTransformer(),
                new OpeningSensorDataTransformer(),
                new PresenceSensorDataTransformer(),
                new StatusSensorDataTransformer()
        );

        handler = new SensorDataHandler(transformers);
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
        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/" + sensorType)
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
            public void transform(String payload, String roomId) {
            }
        };

        SensorDataHandler handler = new SensorDataHandler(List.of(transformer));

        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/air")
        );
    }
}