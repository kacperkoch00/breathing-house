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
                    "sensorId": "sensor-1",
                    "presence": "DETECTED"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("sensor-1", result.sensorId());
        assertNull(result.roomId());
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
                    "sensorId": "sensor-1",
                    "presence": "CLEAR"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals("sensor-1", result.sensorId());
        assertNull(result.roomId());
        assertEquals(SensorType.PRESENCE, result.type());
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
        assertEquals(Map.of("present", false), result.values());
    }

    @Test
    void shouldAcceptLowercaseDetectedState() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "detected"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(Map.of("present", true), result.values());
    }

    @Test
    void shouldAcceptLowercaseClearState() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "clear"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(Map.of("present", false), result.values());
    }

    @Test
    void shouldThrowExceptionForUnknownPresenceState() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "UNKNOWN"
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Unknown presence state: UNKNOWN", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenPresenceMissing() {
        String payload = "{\"sensorId\":\"sensor-1\"}";

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Missing required field: presence", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionForInvalidJson() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "DETECTED",
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Invalid presence sensor payload", exception.getMessage());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldUseObservedAtFromIsoTimestamp() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "DETECTED",
                    "timestamp": "2024-01-15T10:30:00Z"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result.observedAt());
    }

    @Test
    void shouldUseObservedAtFromEpochMillis() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "DETECTED",
                    "timestamp": 1704312600000
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(Instant.ofEpochMilli(1_704_312_600_000L), result.observedAt());
    }

    @Test
    void shouldSetObservedAtAndReceivedAtToNowWhenTimestampMissing() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "DETECTED"
                }
                """;

        Instant before = Instant.now();

        SensorData result = transformer.transform(payload, null);

        Instant after = Instant.now();

        assertFalse(result.observedAt().isBefore(before));
        assertFalse(result.observedAt().isAfter(after));
        assertFalse(result.receivedAt().isBefore(before));
        assertFalse(result.receivedAt().isAfter(after));
    }

    @Test
    void shouldNotExposeDeviceIdInEnvelope() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "presence": "DETECTED",
                    "deviceId": "pir-1"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertNull(result.deviceId());
        assertFalse(result.values().containsKey("deviceId"));
    }
}
