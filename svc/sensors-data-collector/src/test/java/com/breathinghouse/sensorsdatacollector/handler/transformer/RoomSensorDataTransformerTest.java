package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.InvalidSensorPayloadException;
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
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "humidity": 45.2,
                    "light": 320
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("sensor-1", result.sensorId());
        assertNull(result.roomId());
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
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": 9.9
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals("DARK", result.values().get("lightLevel"));
    }

    @Test
    void shouldDetermineDimLightLevel() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": 10
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals("DIM", result.values().get("lightLevel"));
    }

    @Test
    void shouldDetermineNormalLightLevel() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": 100
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals("NORMAL", result.values().get("lightLevel"));
    }

    @Test
    void shouldDetermineBrightLightLevel() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": 500
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals("BRIGHT", result.values().get("lightLevel"));
    }

    @Test
    void shouldThrowExceptionWhenLightMissing() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "humidity": 45.2
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Missing required field: light", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenLightHasWrongType() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": "unknown"
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Field 'light' must be a number", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenTemperatureOutOfRange() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 100,
                    "light": 320
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertTrue(exception.getMessage().contains("temperature"));
    }

    @Test
    void shouldThrowExceptionWhenLightNegative() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": -1
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertTrue(exception.getMessage().contains("light"));
    }

    @Test
    void shouldThrowExceptionForInvalidJson() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": 320,
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Invalid room sensor payload", exception.getMessage());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldUseObservedAtFromIsoTimestamp() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "light": 320,
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
                    "temperature": 22.5,
                    "light": 320,
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
                    "temperature": 22.5,
                    "light": 320
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
                    "temperature": 22.5,
                    "light": 320,
                    "deviceId": "room-sensor-1"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertNull(result.deviceId());
        assertFalse(result.values().containsKey("deviceId"));
    }
}
