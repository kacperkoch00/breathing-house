package com.breathinghouse.homeapi.history;

import java.time.Instant;

public record OccupancyEvent(
        long id,
        String roomId,
        String sensorId,
        EventType eventType,
        Boolean present,
        Boolean open,
        Instant observedAt,
        Instant receivedAt,
        Instant ingestedAt
) {
}
