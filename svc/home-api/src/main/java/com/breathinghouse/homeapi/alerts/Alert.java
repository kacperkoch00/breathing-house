package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertConfiguration.Severity;

import java.time.Instant;

public record Alert(
        long id,
        String ruleId,
        String roomId,
        String sensorId,
        Severity severity,
        AlertStatus status,
        String message,
        String triggerValue,
        Instant triggeredAt,
        Instant resolvedAt,
        Instant lastEvaluatedAt
) {
}
