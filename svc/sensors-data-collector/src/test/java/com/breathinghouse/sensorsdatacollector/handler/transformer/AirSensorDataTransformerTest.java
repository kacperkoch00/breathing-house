package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AirSensorDataTransformerTest {

    private AirSensorDataTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new AirSensorDataTransformer(new ObjectMapper());
    }

    @Test
    void shouldReturnSupportedType() {
        assertEquals(SensorType.AIR, transformer.supportedType());
    }

    @Test
    void shouldTransformValidPayload() {
        String payload = """
                {
                    "temperature": 22.5,
                    "humidity": 45.2,
                    "co2": 650
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("kitchen", result.roomId());
        assertEquals(SensorType.AIR, result.type());
        assertNotNull(result.timestamp());
        assertEquals(Map.of("temperature", 22.5, "humidity", 45.2, "co2", 650), result.values());
    }

    @Test
    void shouldSetTimestampDuringTransformation() {
        String payload = """
                {
                    "temperature": 22.5
                }
                """;

        Instant before = Instant.now();

        SensorData result = transformer.transform(payload, "kitchen");

        Instant after = Instant.now();

        assertFalse(result.timestamp().isBefore(before));
        assertFalse(result.timestamp().isAfter(after));
    }

    @Test
    void shouldThrowExceptionForInvalidPayload() {
        String payload = """
                {
                    "temperature": 22.5,
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals(
                "Invalid air sensor payload: " + payload,
                exception.getMessage()
        );

        assertInstanceOf(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                exception.getCause()
        );
    }

    @Test
    void shouldPreserveDifferentValueTypes() {
        String payload = """
                {
                    "temperature": 22.5,
                    "humidity": 45,
                    "co2": 650,
                    "sensorActive": true,
                    "status": "OK"
                }
                """;

        SensorData result = transformer.transform(payload, "bedroom");

        assertEquals(22.5, result.values().get("temperature"));
        assertEquals(45, result.values().get("humidity"));
        assertEquals(650, result.values().get("co2"));
        assertEquals(true, result.values().get("sensorActive"));
        assertEquals("OK", result.values().get("status"));
    }
}