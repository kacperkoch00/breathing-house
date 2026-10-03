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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({GatewayHeartbeatRepository.class, JdbcConfig.class, ObjectMapper.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class GatewayHeartbeatRepositoryTest {

    @Autowired
    private GatewayHeartbeatRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM home_api.gateway_heartbeat");
    }

    @Test
    void persistsValidHeartbeatAndPayload() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", "OK");
        values.put("uptime", 12);
        values.put("extra", "keep-me");

        repository.insert(new GatewayHeartbeat(
                "gateway",
                null,
                "OK",
                Instant.parse("2026-10-03T10:00:00Z"),
                Instant.parse("2026-10-03T10:00:05Z"),
                values,
                "status-data",
                0,
                11L));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT gateway_id, device_id, reported_status, observed_at, received_at,
                       payload, kafka_topic, kafka_partition, kafka_offset
                FROM home_api.gateway_heartbeat
                """);

        assertThat(row.get("gateway_id")).isEqualTo("gateway");
        assertThat(row.get("device_id")).isNull();
        assertThat(row.get("reported_status")).isEqualTo("OK");
        assertThat(row.get("kafka_topic")).isEqualTo("status-data");
        assertThat(((Number) row.get("kafka_partition")).intValue()).isEqualTo(0);
        assertThat(((Number) row.get("kafka_offset")).longValue()).isEqualTo(11L);
        Object payload = row.get("payload");
        String payloadText = payload instanceof byte[] bytes
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(payload);
        assertThat(payloadText).contains("keep-me");
        assertThat(repository.findLatestReceivedAt("gateway"))
                .contains(Instant.parse("2026-10-03T10:00:05Z"));
    }

    @Test
    void duplicateKafkaCoordinatesAreIdempotent() {
        GatewayHeartbeat heartbeat = sample("gateway", Instant.parse("2026-10-03T10:00:00Z"), 5L);
        repository.insert(heartbeat);
        repository.insert(heartbeat);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM home_api.gateway_heartbeat", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void findsLatestReceivedAtForConfiguredGateway() {
        repository.insert(sample("gateway", Instant.parse("2026-10-03T10:00:00Z"), 1L));
        repository.insert(sample("gateway", Instant.parse("2026-10-03T10:00:30Z"), 2L));
        repository.insert(sample("other", Instant.parse("2026-10-03T11:00:00Z"), 3L));

        assertThat(repository.findLatestReceivedAt("gateway"))
                .contains(Instant.parse("2026-10-03T10:00:30Z"));
        assertThat(repository.findLatestReceivedAt("missing")).isEmpty();
    }

    @Test
    void readinessChecksGatewayHeartbeatTable() {
        assertThat(repository.isReady()).isTrue();
    }

    private static GatewayHeartbeat sample(String gatewayId, Instant receivedAt, long offset) {
        Map<String, Object> values = Map.of("status", "ONLINE");
        return new GatewayHeartbeat(
                gatewayId,
                null,
                "ONLINE",
                receivedAt.minusSeconds(1),
                receivedAt,
                values,
                "status-data",
                0,
                offset);
    }
}
