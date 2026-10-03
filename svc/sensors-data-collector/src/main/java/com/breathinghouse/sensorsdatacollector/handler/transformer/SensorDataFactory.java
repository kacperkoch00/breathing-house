package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class SensorDataFactory {

    private static final double EPOCH_MILLIS_THRESHOLD = 1e12;

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "schemaVersion",
            "sensorId",
            "roomId",
            "deviceId",
            "type",
            "timestamp",
            "observedAt",
            "receivedAt"
    );

    private SensorDataFactory() {
    }

    /** Schema-v2 envelope: identified by {@code sensorId}, no room or device identity. */
    static SensorData createSensorData(
            SensorType type,
            String sensorId,
            Object timestamp,
            Map<String, Object> values
    ) {
        Instant receivedAt = Instant.now();
        Instant observedAt = resolveObservedAt(timestamp, receivedAt);
        return new SensorData(
                SensorData.SCHEMA_VERSION,
                sensorId,
                null,
                null,
                type,
                observedAt,
                receivedAt,
                withoutEnvelopeFields(values)
        );
    }

    /** Schema-v1 status envelope: keeps {@code roomId} / {@code deviceId} as before. */
    static SensorData createStatus(String roomId, Map<String, Object> values) {
        Instant receivedAt = Instant.now();
        Instant observedAt = resolveObservedAt(values.get("timestamp"), receivedAt);
        String deviceId = values.get("deviceId") instanceof String text ? text : null;
        return new SensorData(
                SensorData.STATUS_SCHEMA_VERSION,
                null,
                roomId,
                deviceId,
                SensorType.STATUS,
                observedAt,
                receivedAt,
                values
        );
    }

    private static Map<String, Object> withoutEnvelopeFields(Map<String, Object> values) {
        Map<String, Object> result = new HashMap<>(values);
        result.keySet().removeAll(ENVELOPE_FIELDS);
        return result;
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

    private static Instant fromEpochNumber(double epoch) {
        if (epoch > EPOCH_MILLIS_THRESHOLD) {
            return Instant.ofEpochMilli((long) epoch);
        }
        return Instant.ofEpochSecond((long) epoch);
    }
}
