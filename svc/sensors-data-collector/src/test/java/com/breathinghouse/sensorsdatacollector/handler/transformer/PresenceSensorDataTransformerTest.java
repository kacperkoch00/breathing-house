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

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("kitchen", result.roomId());
        assertNull(result.deviceId());
        assertEquals(SensorType.PRESENCE, result.type());
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
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
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
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
    void shouldUseObservedAtFromIsoTimestamp() {
        String payload = """
                {
                    "presence": "DETECTED",
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
                    "presence": "DETECTED",
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
                    "presence": "DETECTED"
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
                    "presence": "DETECTED",
                    "deviceId": "pir-1"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("pir-1", result.deviceId());
    }
}
