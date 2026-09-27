package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.InvalidSensorPayloadException;
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

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("kitchen", result.roomId());
        assertNull(result.deviceId());
        assertEquals(SensorType.OPENING, result.type());
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
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
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
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

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Unknown opening state: UNKNOWN", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenStateMissing() {
        String payload = "{}";

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Missing required field: state", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionForInvalidJson() {
        String payload = """
                {
                    "state": "OPEN",
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Invalid opening sensor payload", exception.getMessage());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldUseObservedAtFromIsoTimestamp() {
        String payload = """
                {
                    "state": "OPEN",
                    "timestamp": "2024-01-15T10:30:00Z"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result.observedAt());
    }

    @Test
    void shouldUseObservedAtFromEpochMillis() {
        String payload = """
                {
                    "state": "OPEN",
                    "timestamp": 1704312600000
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(Instant.ofEpochMilli(1_704_312_600_000L), result.observedAt());
    }

    @Test
    void shouldSetObservedAtAndReceivedAtToNowWhenTimestampMissing() {
        String payload = """
                {
                    "state": "OPEN"
                }
                """;

        Instant before = Instant.now();

        SensorData result = transformer.transform(payload, "kitchen");

        Instant after = Instant.now();

        assertFalse(result.observedAt().isBefore(before));
        assertFalse(result.observedAt().isAfter(after));
        assertFalse(result.receivedAt().isBefore(before));
        assertFalse(result.receivedAt().isAfter(after));
    }

    @Test
    void shouldSetDeviceIdWhenPresent() {
        String payload = """
                {
                    "state": "OPEN",
                    "deviceId": "door-1"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("door-1", result.deviceId());
    }
}
