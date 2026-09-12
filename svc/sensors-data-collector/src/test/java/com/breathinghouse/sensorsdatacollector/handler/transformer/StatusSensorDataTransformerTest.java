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

        assertNull(result.roomId());
        assertEquals(SensorType.STATUS, result.type());
        assertNotNull(result.timestamp());
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
    void shouldSetTimestampDuringTransformation() {
        String payload = """
                {
                    "status": "ONLINE"
                }
                """;

        Instant before = Instant.now();

        SensorData result = transformer.transform(payload, null);

        Instant after = Instant.now();

        assertFalse(result.timestamp().isBefore(before));
        assertFalse(result.timestamp().isAfter(after));
    }
}