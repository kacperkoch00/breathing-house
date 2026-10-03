package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.JacksonUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensorDataFactoryTest {

    private static SensorData create(Map<String, Object> values) {
        return SensorDataFactory.createSensorData(SensorType.AIR, "sensor-1", values.get("timestamp"), values);
    }

    @Test
    void shouldUseIsoTimestamp() {
        Instant expected = Instant.parse("2024-01-15T10:30:00Z");

        SensorData result = create(Map.of("temperature", 22.5, "timestamp", "2024-01-15T10:30:00Z"));

        assertEquals(expected, result.observedAt());
    }

    @Test
    void shouldUseEpochMillis() {
        long epochMillis = 1_704_312_600_000L;

        SensorData result = create(Map.of("temperature", 22.5, "timestamp", epochMillis));

        assertEquals(Instant.ofEpochMilli(epochMillis), result.observedAt());
    }

    @Test
    void shouldUseEpochSeconds() {
        long epochSeconds = 1_704_312_600L;

        SensorData result = create(Map.of("temperature", 22.5, "timestamp", epochSeconds));

        assertEquals(Instant.ofEpochSecond(epochSeconds), result.observedAt());
    }

    @Test
    void shouldFallbackToNowWhenTimestampMissing() {
        Instant before = Instant.now();
        SensorData result = create(Map.of("temperature", 22.5));
        Instant after = Instant.now();

        assertFalse(result.observedAt().isBefore(before));
        assertFalse(result.observedAt().isAfter(after));
        assertEquals(result.observedAt(), result.receivedAt());
    }

    @Test
    void shouldFallbackToNowWhenTimestampUnparseable() {
        Instant before = Instant.now();
        Map<String, Object> values = new HashMap<>();
        values.put("timestamp", "not-a-timestamp");

        SensorData result = create(values);
        Instant after = Instant.now();

        assertFalse(result.observedAt().isBefore(before));
        assertFalse(result.observedAt().isAfter(after));
    }

    @Test
    void shouldBuildSchemaV2EnvelopeWithSensorIdAndNoRoomOrDeviceIdentity() {
        Instant before = Instant.now();
        SensorData result = create(Map.of("temperature", 22.5));
        Instant after = Instant.now();

        assertEquals(2, result.schemaVersion());
        assertEquals(SensorData.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("sensor-1", result.sensorId());
        assertNull(result.roomId());
        assertNull(result.deviceId());
        assertEquals(SensorType.AIR, result.type());
        assertFalse(result.receivedAt().isBefore(before));
        assertFalse(result.receivedAt().isAfter(after));
    }

    @Test
    void shouldExcludeEnvelopeFieldsFromValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("temperature", 22.5);
        values.put("sensorId", "sensor-1");
        values.put("timestamp", 1_704_312_600L);
        values.put("deviceId", "dev-1");
        values.put("roomId", "kitchen");
        values.put("schemaVersion", 1);

        SensorData result = create(values);

        assertEquals(Map.of("temperature", 22.5), result.values());
    }

    @Test
    void shouldKeepStatusAsSchemaV1WithRoomAndDeviceId() {
        Map<String, Object> values = Map.of("status", "ONLINE", "deviceId", "gw-1");

        SensorData result = SensorDataFactory.createStatus("gateway", values);

        assertEquals(1, result.schemaVersion());
        assertNull(result.sensorId());
        assertEquals("gateway", result.roomId());
        assertEquals("gw-1", result.deviceId());
        assertEquals(SensorType.STATUS, result.type());
        assertEquals(values, result.values());
    }

    @Test
    void shouldIgnoreNonTextualStatusDeviceId() {
        SensorData result = SensorDataFactory.createStatus("gateway", Map.of("deviceId", 42));

        assertNull(result.deviceId());
    }

    @Test
    void shouldSerializeSchemaV2WithoutRoomIdOrDeviceId() {
        SensorData result = create(Map.of("temperature", 22.5));

        JsonNode json = JacksonUtils.enhancedObjectMapper().valueToTree(result);

        assertEquals(2, json.get("schemaVersion").asInt());
        assertEquals("sensor-1", json.get("sensorId").asText());
        assertFalse(json.has("roomId"));
        assertFalse(json.has("deviceId"));
    }

    @Test
    void shouldSerializeStatusWithRoomIdAndWithoutSensorId() {
        SensorData result = SensorDataFactory.createStatus("gateway", Map.of("status", "ONLINE"));

        JsonNode json = JacksonUtils.enhancedObjectMapper().valueToTree(result);

        assertEquals(1, json.get("schemaVersion").asInt());
        assertEquals("gateway", json.get("roomId").asText());
        assertFalse(json.has("sensorId"));
    }
}
