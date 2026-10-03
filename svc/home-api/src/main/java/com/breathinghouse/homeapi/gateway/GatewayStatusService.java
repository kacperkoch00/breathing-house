package com.breathinghouse.homeapi.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class GatewayStatusService {

    private final GatewayHeartbeatRepository repository;
    private final Clock clock;
    private final String gatewayId;
    private final Duration heartbeatTimeout;

    public GatewayStatusService(
            GatewayHeartbeatRepository repository,
            Clock clock,
            @Value("${home-api.sensor-gateway.id}") String gatewayId,
            @Value("${home-api.sensor-gateway.heartbeat-timeout}") Duration heartbeatTimeout) {
        this.repository = repository;
        this.clock = clock;
        this.gatewayId = gatewayId;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public boolean isOnline() {
        return repository.findLatestReceivedAt(gatewayId)
                .map(receivedAt -> !receivedAt.isBefore(clock.instant().minus(heartbeatTimeout)))
                .orElse(false);
    }
}
