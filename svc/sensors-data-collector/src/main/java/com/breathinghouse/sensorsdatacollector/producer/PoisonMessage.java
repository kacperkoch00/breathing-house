package com.breathinghouse.sensorsdatacollector.producer;

import java.time.Instant;

public record PoisonMessage(
        Instant rejectedAt,
        String mqttTopic,
        String roomId,
        String sensorType,
        String reason,
        String payload
) {
}
