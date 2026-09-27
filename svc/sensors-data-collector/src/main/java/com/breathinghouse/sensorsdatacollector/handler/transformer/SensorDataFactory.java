package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

final class SensorDataFactory {

    private static final double EPOCH_MILLIS_THRESHOLD = 1e12;

    private SensorDataFactory() {
    }

    static SensorData create(String roomId, SensorType type, Map<String, Object> values) {
        Instant receivedAt = Instant.now();
        Instant observedAt = resolveObservedAt(values.get("timestamp"), receivedAt);
        String deviceId = resolveDeviceId(values.get("deviceId"));
        return new SensorData(
                SensorData.SCHEMA_VERSION,
                roomId,
                deviceId,
                type,
                observedAt,
                receivedAt,
                values
        );
    }

    static SensorData create(String roomId, SensorType type, JsonNode root, Map<String, Object> values) {
        Instant receivedAt = Instant.now();
        Instant observedAt = resolveObservedAt(root.get("timestamp"), receivedAt);
        String deviceId = resolveDeviceId(root.get("deviceId"));
        return new SensorData(
                SensorData.SCHEMA_VERSION,
                roomId,
                deviceId,
                type,
                observedAt,
                receivedAt,
                values
        );
    }

    private static Instant resolveObservedAt(Object timestamp, Instant fallback) {
        if (timestamp == null) {
            return fallback;
        }

        try {
            if (timestamp instanceof Number number) {
                return fromEpochNumber(number.doubleValue());
            }
            if (timestamp instanceof String text) {
                return Instant.parse(text);
            }
        } catch (RuntimeException ignored) {
            // treat unparseable timestamp as missing
        }

        return fallback;
    }

    private static Instant resolveObservedAt(JsonNode timestamp, Instant fallback) {
        if (timestamp == null || timestamp.isNull() || timestamp.isMissingNode()) {
            return fallback;
        }

        try {
            if (timestamp.isNumber()) {
                return fromEpochNumber(timestamp.asDouble());
            }
            if (timestamp.isTextual()) {
                return Instant.parse(timestamp.asText());
            }
        } catch (RuntimeException ignored) {
            // treat unparseable timestamp as missing
        }

        return fallback;
    }

    private static Instant fromEpochNumber(double epoch) {
        if (epoch > EPOCH_MILLIS_THRESHOLD) {
            return Instant.ofEpochMilli((long) epoch);
        }
        return Instant.ofEpochSecond((long) epoch);
    }

    private static String resolveDeviceId(Object deviceId) {
        return deviceId instanceof String text ? text : null;
    }

    private static String resolveDeviceId(JsonNode deviceId) {
        if (deviceId != null && deviceId.isTextual()) {
            return deviceId.asText();
        }
        return null;
    }
}
