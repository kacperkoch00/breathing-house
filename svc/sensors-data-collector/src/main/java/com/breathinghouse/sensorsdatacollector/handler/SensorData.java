package com.breathinghouse.sensorsdatacollector.handler;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SensorData(
        int schemaVersion,
        String sensorId,
        String roomId,
        String deviceId,
        SensorType type,
        Instant observedAt,
        Instant receivedAt,
        Map<String, Object> values
) {
    /** Schema version for sensor and occupancy envelopes identified by {@code sensorId}. */
    public static final int SCHEMA_VERSION = 2;

    /** Schema version for gateway status envelopes, which keep {@code roomId} / {@code deviceId}. */
    public static final int STATUS_SCHEMA_VERSION = 1;
}
