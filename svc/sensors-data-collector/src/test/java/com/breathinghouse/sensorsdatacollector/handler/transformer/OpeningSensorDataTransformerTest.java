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

class OpeningSensorDataTransformerTest {

    private OpeningSensorDataTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new OpeningSensorDataTransformer(new ObjectMapper());
    }

    @Test
    void shouldReturnSupportedType() {
        assertEquals(SensorType.OPENING, transformer.supportedType());
    }

    @Test
    void shouldTransformOpenState() {
        String payload = """
                {
                    "state": "OPEN"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("kitchen", result.roomId());
        assertEquals(SensorType.OPENING, result.type());
        assertNotNull(result.timestamp());
        assertEquals(Map.of("open", true), result.values());
    }

    @Test
    void shouldTransformClosedState() {
        String payload = """
                {
                    "state": "CLOSED"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("kitchen", result.roomId());
        assertEquals(SensorType.OPENING, result.type());
        assertNotNull(result.timestamp());
        assertEquals(Map.of("open", false), result.values());
    }

    @Test
    void shouldAcceptLowercaseOpenState() {
        String payload = """
                {
                    "state": "open"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(Map.of("open", true), result.values());
    }

    @Test
    void shouldAcceptLowercaseClosedState() {
        String payload = """
                {
                    "state": "closed"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(Map.of("open", false), result.values());
    }

    @Test
    void shouldThrowExceptionForUnknownOpeningState() {
        String payload = """
                {
                    "state": "UNKNOWN"
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Unknown opening state: UNKNOWN", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionForInvalidPayload() {
        String payload = """
                {
                    "state": "OPEN",
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Invalid opening sensor payload: " + payload, exception.getMessage());

        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldSetTimestampDuringTransformation() {
        String payload = """
                {
                    "state": "OPEN"
                }
                """;

        Instant before = Instant.now();

        SensorData result = transformer.transform(payload, "kitchen");

        Instant after = Instant.now();

        assertFalse(result.timestamp().isBefore(before));
        assertFalse(result.timestamp().isAfter(after));
    }
}