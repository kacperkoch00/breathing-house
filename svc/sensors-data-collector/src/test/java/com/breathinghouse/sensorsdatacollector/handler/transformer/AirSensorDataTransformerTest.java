package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
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
                    "temperature": 22.5,
                    "humidity": 45.2,
                    "co2": 650
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("kitchen", result.roomId());
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
                    "temperature": 22.5
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
                    "deviceId": "air-sensor-1"
                }
                """;

        SensorData result = transformer.transform(payload, "kitchen");

        assertEquals("air-sensor-1", result.deviceId());
    }

    @Test
    void shouldThrowExceptionForInvalidPayload() {
        String payload = """
                {
                    "temperature": 22.5,
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transformer.transform(payload, "kitchen")
        );

        assertEquals(
                "Invalid air sensor payload: " + payload,
                exception.getMessage()
        );

        assertInstanceOf(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                exception.getCause()
        );
    }

    @Test
    void shouldPreserveDifferentValueTypes() {
        String payload = """
                {
                    "temperature": 22.5,
                    "humidity": 45,
                    "co2": 650,
                    "sensorActive": true,
                    "status": "OK"
                }
                """;

        SensorData result = transformer.transform(payload, "bedroom");

        assertEquals(22.5, result.values().get("temperature"));
        assertEquals(45, result.values().get("humidity"));
        assertEquals(650, result.values().get("co2"));
        assertEquals(true, result.values().get("sensorActive"));
        assertEquals("OK", result.values().get("status"));
    }
}
