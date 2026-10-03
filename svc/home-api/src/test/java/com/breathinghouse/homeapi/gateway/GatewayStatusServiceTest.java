package com.breathinghouse.homeapi.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Mock
    private GatewayHeartbeatRepository repository;

    @Test
    void returnsFalseWhenNoHeartbeatExists() {
        when(repository.findLatestReceivedAt("gateway")).thenReturn(Optional.empty());

        GatewayStatusService service = service("gateway", Duration.ofSeconds(30));

        assertThat(service.isOnline()).isFalse();
    }

    @Test
    void returnsTrueWhenHeartbeatIsFresh() {
        when(repository.findLatestReceivedAt("gateway"))
                .thenReturn(Optional.of(NOW.minusSeconds(10)));

        GatewayStatusService service = service("gateway", Duration.ofSeconds(30));

        assertThat(service.isOnline()).isTrue();
    }

    @Test
    void returnsFalseWhenHeartbeatExpired() {
        when(repository.findLatestReceivedAt("gateway"))
                .thenReturn(Optional.of(NOW.minusSeconds(31)));

        GatewayStatusService service = service("gateway", Duration.ofSeconds(30));

        assertThat(service.isOnline()).isFalse();
    }

    @Test
    void usesReceivedAtBoundaryInclusively() {
        when(repository.findLatestReceivedAt("gateway"))
                .thenReturn(Optional.of(NOW.minusSeconds(30)));

        GatewayStatusService service = service("gateway", Duration.ofSeconds(30));

        assertThat(service.isOnline()).isTrue();
    }

    @Test
    void usesConfiguredGatewayId() {
        when(repository.findLatestReceivedAt("custom-gw"))
                .thenReturn(Optional.of(NOW.minusSeconds(1)));

        GatewayStatusService service = service("custom-gw", Duration.ofSeconds(30));

        assertThat(service.isOnline()).isTrue();
        verify(repository).findLatestReceivedAt("custom-gw");
    }

    private GatewayStatusService service(String gatewayId, Duration timeout) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new GatewayStatusService(repository, clock, gatewayId, timeout);
    }
}
