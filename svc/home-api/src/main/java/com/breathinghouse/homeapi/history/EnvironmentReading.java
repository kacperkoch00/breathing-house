package com.breathinghouse.homeapi.history;

import java.time.Instant;

public record EnvironmentReading(
        long id,
        String roomId,
        String sensorId,
        SensorType sensorType,
        Double temperature,
        Double humidity,
        Double co2,
        Double light,
        String lightLevel,
        Instant observedAt,
        Instant receivedAt,
        Instant ingestedAt
) {
}
