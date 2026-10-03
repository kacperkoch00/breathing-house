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

class AirSensorDataTransformerTest {

    private AirSensorDataTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new AirSensorDataTransformer(new ObjectMapper());
    }

    @Test
    void shouldReturnSupportedType() {
        assertEquals(SensorType.AIR, transformer.supportedType());
    }

    @Test
    void shouldTransformValidPayload() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "humidity": 45.2,
                    "co2": 650
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("sensor-1", result.sensorId());
        assertNull(result.roomId());
        assertNull(result.deviceId());
        assertEquals(SensorType.AIR, result.type());
        assertNotNull(result.observedAt());
        assertNotNull(result.receivedAt());
        assertEquals(Map.of("temperature", 22.5, "humidity", 45.2, "co2", 650), result.values());
    }

    @Test
    void shouldUseObservedAtFromIsoTimestamp() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "humidity": 45.2,
                    "co2": 650,
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
                    "humidity": 45.2,
                    "co2": 650,
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
                    "humidity": 45.2,
                    "co2": 650
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
                    "humidity": 45.2,
                    "co2": 650,
                    "deviceId": "air-sensor-1"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertNull(result.deviceId());
        assertFalse(result.values().containsKey("deviceId"));
    }

    @Test
    void shouldThrowExceptionForInvalidJson() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Invalid air sensor payload", exception.getMessage());
        assertInstanceOf(JsonProcessingException.class, exception.getCause());
    }

    @Test
    void shouldThrowExceptionWhenRequiredFieldMissing() {
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

        assertEquals("Missing required field: co2", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenTemperatureHasWrongType() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": "warm",
                    "humidity": 45.2,
                    "co2": 650
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertEquals("Field 'temperature' must be a number", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWhenHumidityOutOfRange() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "humidity": 120,
                    "co2": 650
                }
                """;

        InvalidSensorPayloadException exception = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload, null)
        );

        assertTrue(exception.getMessage().contains("humidity"));
    }

    @Test
    void shouldPreserveDifferentValueTypes() {
        String payload = """
                {
                    "sensorId": "sensor-1",
                    "temperature": 22.5,
                    "humidity": 45,
                    "co2": 650,
                    "sensorActive": true,
                    "status": "OK"
                }
                """;

        SensorData result = transformer.transform(payload, null);

        assertEquals(22.5, result.values().get("temperature"));
        assertEquals(45, result.values().get("humidity"));
        assertEquals(650, result.values().get("co2"));
        assertEquals(true, result.values().get("sensorActive"));
        assertEquals("OK", result.values().get("status"));
    }
}
