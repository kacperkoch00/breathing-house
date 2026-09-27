package com.breathinghouse.sensorsdatacollector.handler;

import java.time.Instant;
import java.util.Map;

public record SensorData(
        int schemaVersion,
        String roomId,
        String deviceId,
        SensorType type,
        Instant observedAt,
        Instant receivedAt,
        Map<String, Object> values
) {
    public static final int SCHEMA_VERSION = 1;
}
