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

        assertEquals("kitchen", result.roomId());
        assertEquals(SensorType.ROOM, result.type());
        assertNotNull(result.timestamp());
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
    void shouldSetTimestampDuringTransformation() {
        String payload = """
                {
                    "temperature": 22.5,
                    "light": 320
                }
                """;

        Instant before = Instant.now();

        SensorData result = transformer.transform(payload, "kitchen");

        Instant after = Instant.now();

        assertFalse(result.timestamp().isBefore(before));
        assertFalse(result.timestamp().isAfter(after));
    }
}