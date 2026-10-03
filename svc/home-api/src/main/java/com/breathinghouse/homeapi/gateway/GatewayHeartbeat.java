package com.breathinghouse.homeapi.gateway;

import java.time.Instant;
import java.util.Map;

public record GatewayHeartbeat(
        String gatewayId,
        String deviceId,
        String reportedStatus,
        Instant observedAt,
        Instant receivedAt,
        Map<String, Object> payload,
        String kafkaTopic,
        int kafkaPartition,
        long kafkaOffset) {
}
