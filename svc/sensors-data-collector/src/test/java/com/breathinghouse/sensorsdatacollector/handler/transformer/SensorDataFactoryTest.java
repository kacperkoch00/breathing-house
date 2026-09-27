package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensorDataFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldUseIsoTimestampFromMap() {
        Instant expected = Instant.parse("2024-01-15T10:30:00Z");
        Map<String, Object> values = Map.of("temperature", 22.5, "timestamp", "2024-01-15T10:30:00Z");

        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, values);

        assertEquals(expected, result.observedAt());
    }

    @Test
    void shouldUseEpochMillisFromMap() {
        long epochMillis = 1_704_312_600_000L;
        Map<String, Object> values = Map.of("temperature", 22.5, "timestamp", epochMillis);

        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, values);

        assertEquals(Instant.ofEpochMilli(epochMillis), result.observedAt());
    }

    @Test
    void shouldUseEpochSecondsFromMap() {
        long epochSeconds = 1_704_312_600L;
        Map<String, Object> values = Map.of("temperature", 22.5, "timestamp", epochSeconds);

        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, values);

        assertEquals(Instant.ofEpochSecond(epochSeconds), result.observedAt());
    }

    @Test
    void shouldFallbackToNowWhenTimestampMissingFromMap() {
        Instant before = Instant.now();
        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, Map.of("temperature", 22.5));
        Instant after = Instant.now();

        assertFalse(result.observedAt().isBefore(before));
        assertFalse(result.observedAt().isAfter(after));
        assertEquals(result.observedAt(), result.receivedAt());
    }

    @Test
    void shouldFallbackToNowWhenTimestampUnparseableFromMap() {
        Instant before = Instant.now();
        Map<String, Object> values = new HashMap<>();
        values.put("timestamp", "not-a-timestamp");

        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, values);
        Instant after = Instant.now();

        assertFalse(result.observedAt().isBefore(before));
        assertFalse(result.observedAt().isAfter(after));
    }

    @Test
    void shouldReadDeviceIdFromMap() {
        Map<String, Object> values = Map.of("temperature", 22.5, "deviceId", "sensor-1");

        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, values);

        assertEquals("sensor-1", result.deviceId());
    }

    @Test
    void shouldLeaveDeviceIdNullWhenMissingFromMap() {
        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, Map.of("temperature", 22.5));

        assertNull(result.deviceId());
    }

    @Test
    void shouldIgnoreNonTextualDeviceIdFromMap() {
        Map<String, Object> values = Map.of("deviceId", 42);

        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, values);

        assertNull(result.deviceId());
    }

    @Test
    void shouldUseIsoTimestampFromJsonNode() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("state", "OPEN");
        root.put("timestamp", "2024-01-15T10:30:00Z");

        SensorData result = SensorDataFactory.create(
                "kitchen",
                SensorType.OPENING,
                root,
                Map.of("open", true)
        );

        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result.observedAt());
    }

    @Test
    void shouldUseEpochMillisFromJsonNode() {
        long epochMillis = 1_704_312_600_000L;
        ObjectNode root = objectMapper.createObjectNode();
        root.put("state", "OPEN");
        root.put("timestamp", epochMillis);

        SensorData result = SensorDataFactory.create(
                "kitchen",
                SensorType.OPENING,
                root,
                Map.of("open", true)
        );

        assertEquals(Instant.ofEpochMilli(epochMillis), result.observedAt());
    }

    @Test
    void shouldReadDeviceIdFromJsonNode() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("state", "OPEN");
        root.put("deviceId", "door-1");

        SensorData result = SensorDataFactory.create(
                "kitchen",
                SensorType.OPENING,
                root,
                Map.of("open", true)
        );

        assertEquals("door-1", result.deviceId());
    }

    @Test
    void shouldLeaveDeviceIdNullWhenMissingFromJsonNode() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("state", "OPEN");

        SensorData result = SensorDataFactory.create(
                "kitchen",
                SensorType.OPENING,
                root,
                Map.of("open", true)
        );

        assertNull(result.deviceId());
    }

    @Test
    void shouldSetSchemaVersionAndReceivedAt() {
        Instant before = Instant.now();
        SensorData result = SensorDataFactory.create("kitchen", SensorType.AIR, Map.of());
        Instant after = Instant.now();

        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals(1, result.schemaVersion());
        assertNotNull(result.receivedAt());
        assertFalse(result.receivedAt().isBefore(before));
        assertFalse(result.receivedAt().isAfter(after));
    }
}
