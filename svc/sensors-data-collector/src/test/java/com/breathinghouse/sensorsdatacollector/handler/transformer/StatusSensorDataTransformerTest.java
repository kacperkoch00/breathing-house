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

class StatusSensorDataTransformerTest {

    private StatusSensorDataTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new StatusSensorDataTransformer(new ObjectMapper());
    }

    @Test
    void shouldReturnSupportedType() {
        assertEquals(SensorType.STATUS, transformer.supportedType());
    }

    @Test
    void shouldTransformValidPayload() {
        String payload = """
                {
                    "status": "ONLINE",
                    "uptime": 3600,
                    "connected": true
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertNull(result.roomId());
        assertNull(result.deviceId());
        assertEquals(SensorType.STATUS, result.type());
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
        assertEquals(
                Map.of(
                        "status", "ONLINE",
                        "uptime", 3600,
                        "connected", true
                ),
                result.values()
        );
    }

    @Test
    void shouldPreserveDifferentValueTypes() {
        String payload = """
                {
                    "status": "ONLINE",
                    "uptime": 3600,
                    "temperature": 22.5,
                    "connected": true
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals("ONLINE", result.values().get("status"));
        assertEquals(3600, result.values().get("uptime"));
        assertEquals(22.5, result.values().get("temperature"));
        assertEquals(true, result.values().get("connected"));
    }

    @Test
    void shouldPreserveRoomId() {
        String payload = """
                {
                    "status": "ONLINE"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("kitchen", result.roomId());
        assertEquals(SensorType.STATUS, result.type());
        assertEquals(Map.of("status", "ONLINE"), result.values());
    }

    @Test
    void shouldThrowExceptionForInvalidPayload() {
        String payload = """
                {
                    "status": "ONLINE",
                }
                """;

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> transformer.transform(payload, null));

        assertEquals("Invalid status sensor payload: " + payload, exception.getMessage());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldUseObservedAtFromIsoTimestamp() {
        String payload = """
                {
                    "status": "ONLINE",
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
                    "status": "ONLINE",
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
                    "status": "ONLINE"
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
    void shouldSetDeviceIdWhenPresent() {
        String payload = """
                {
                    "status": "ONLINE",
                    "deviceId": "gateway-1"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals("gateway-1", result.deviceId());
    }
}
