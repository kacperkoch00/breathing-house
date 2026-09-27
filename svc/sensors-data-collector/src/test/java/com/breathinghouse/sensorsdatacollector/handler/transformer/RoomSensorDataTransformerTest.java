package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RoomSensorDataTransformerTest {

    private RoomSensorDataTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new RoomSensorDataTransformer(new ObjectMapper());
    }

    @Test
    void shouldReturnSupportedType() {
        assertEquals(SensorType.ROOM, transformer.supportedType());
    }

    @Test
    void shouldTransformRoomSensorPayload() {
        String payload = """
                {
                    "temperature": 22.5,
                    "humidity": 45.2,
                    "light": 320
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("kitchen", result.roomId());
        assertNull(result.deviceId());
        assertEquals(SensorType.ROOM, result.type());
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
        assertEquals(22.5, result.values().get("temperature"));
        assertEquals(45.2, result.values().get("humidity"));
        assertEquals(320, result.values().get("light"));
        assertEquals("NORMAL", result.values().get("lightLevel"));
    }

    @Test
    void shouldDetermineDarkLightLevel() {
        String payload = """
                {
                    "light": 9.9
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("DARK", result.values().get("lightLevel"));
    }

    @Test
    void shouldDetermineDimLightLevel() {
        String payload = """
                {
                    "light": 10
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("DIM", result.values().get("lightLevel"));
    }

    @Test
    void shouldDetermineNormalLightLevel() {
        String payload = """
                {
                    "light": 100
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("NORMAL", result.values().get("lightLevel"));
    }

    @Test
    void shouldDetermineBrightLightLevel() {
        String payload = """
                {
                    "light": 500
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("BRIGHT", result.values().get("lightLevel"));
    }

    @Test
    void shouldNotAddLightLevelWhenLightValueIsMissing() {
        String payload = """
                {
                    "temperature": 22.5,
                    "humidity": 45.2
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(22.5, result.values().get("temperature"));
        assertEquals(45.2, result.values().get("humidity"));
        assertFalse(result.values().containsKey("lightLevel"));
    }

    @Test
    void shouldNotAddLightLevelWhenLightValueIsNotNumeric() {
        String payload = """
                {
                    "light": "unknown"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("unknown", result.values().get("light"));
        assertFalse(result.values().containsKey("lightLevel"));
    }

    @Test
    void shouldThrowExceptionForInvalidPayload() {
        String payload = """
                {
                    "temperature": 22.5,
                    "light": 320,
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals("Invalid room sensor payload: " + payload, exception.getMessage());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldUseObservedAtFromIsoTimestamp() {
        String payload = """
                {
                    "temperature": 22.5,
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
                    "temperature": 22.5,
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
                    "temperature": 22.5,
                    "light": 320
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
                    "temperature": 22.5,
                    "deviceId": "room-sensor-1"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("room-sensor-1", result.deviceId());
    }
}
