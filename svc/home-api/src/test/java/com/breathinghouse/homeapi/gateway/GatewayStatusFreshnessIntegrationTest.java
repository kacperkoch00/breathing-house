package com.breathinghouse.homeapi.gateway;

import com.breathinghouse.homeapi.config.JdbcConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({GatewayHeartbeatRepository.class, JdbcConfig.class, ObjectMapper.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class GatewayStatusFreshnessIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Autowired
    private GatewayHeartbeatRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM home_api.gateway_heartbeat");
    }

    @Test
    void receivedAtControlsFreshnessNotObservedAtOrIngestedAt() {
        jdbc.update("""
                INSERT INTO home_api.gateway_heartbeat (
                  gateway_id, device_id, reported_status, observed_at, received_at, ingested_at,
                  payload, kafka_topic, kafka_partition, kafka_offset
                ) VALUES (?, NULL, 'ONLINE', ?, ?, ?, CAST(? AS json), 'status-data', 0, 1)
                """,
                "gateway",
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.minusSeconds(120)),
                Timestamp.from(NOW.minusSeconds(1)),
                "{\"status\":\"ONLINE\"}");

        GatewayStatusService service = new GatewayStatusService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "gateway",
                Duration.ofSeconds(30));

        assertThat(service.isOnline()).isFalse();
    }

    @Test
    void freshReceivedAtReturnsOnlineEvenWhenObservedAtIsOld() {
        jdbc.update("""
                INSERT INTO home_api.gateway_heartbeat (
                  gateway_id, device_id, reported_status, observed_at, received_at, ingested_at,
                  payload, kafka_topic, kafka_partition, kafka_offset
                ) VALUES (?, NULL, 'OK', ?, ?, ?, CAST(? AS json), 'status-data', 0, 2)
                """,
                "gateway",
                Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW.minusSeconds(5)),
                Timestamp.from(NOW.minusSeconds(3600)),
                "{\"status\":\"OK\"}");

        GatewayStatusService service = new GatewayStatusService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "gateway",
                Duration.ofSeconds(30));

        assertThat(service.isOnline()).isTrue();
        assertThat(repository.findLatestReceivedAt("gateway")).contains(NOW.minusSeconds(5));
        Map<String, Object> row = jdbc.queryForMap("SELECT reported_status FROM home_api.gateway_heartbeat");
        assertThat(row.get("reported_status")).isEqualTo("OK");
    }
}
