package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PresenceSensorDataTransformerTest {

    private PresenceSensorDataTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new PresenceSensorDataTransformer(new ObjectMapper());
    }

    @Test
    void shouldReturnSupportedType() {
        assertEquals(SensorType.PRESENCE, transformer.supportedType());
    }

    @Test
    void shouldTransformDetectedState() {
        String payload = """
                {
                    "presence": "DETECTED"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("kitchen", result.roomId());
        assertEquals(SensorType.PRESENCE, result.type());
        assertNotNull(result.timestamp());
        assertEquals(Map.of("present", true), result.values());
    }

    @Test
    void shouldTransformClearState() {
        String payload = """
                {
                    "presence": "CLEAR"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("kitchen", result.roomId());
        assertEquals(SensorType.PRESENCE, result.type());
        assertNotNull(result.timestamp());
        assertEquals(Map.of("present", false), result.values());
    }

    @Test
    void shouldAcceptLowercaseDetectedState() {
        String payload = """
                {
                    "presence": "detected"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(Map.of("present", true), result.values());
    }

    @Test
    void shouldAcceptLowercaseClearState() {
        String payload = """
                {
                    "presence": "clear"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(Map.of("present", false), result.values());
    }

    @Test
    void shouldThrowExceptionForUnknownPresenceState() {
        String payload = """
                {
                    "presence": "UNKNOWN"
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Unknown presence state: UNKNOWN", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionForInvalidPayload() {
        String payload = """
                {
                    "presence": "DETECTED",
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Invalid presence sensor payload: " + payload, exception.getMessage());

        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldSetTimestampDuringTransformation() {
        String payload = """
                {
                    "presence": "DETECTED"
                }
                """;

        Instant before = Instant.now();

        SensorData result = transformer.transform(payload, "kitchen");

        Instant after = Instant.now();

        assertFalse(result.timestamp().isBefore(before));
        assertFalse(result.timestamp().isAfter(after));
    }
}