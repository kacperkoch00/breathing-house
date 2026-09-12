package com.breathinghouse.sensorsdatacollector.handler;

import java.time.Instant;
import java.util.Map;

public record SensorData(
        String roomId,
        SensorType type,
        Instant timestamp,
        Map<String, Object> values
) {
}