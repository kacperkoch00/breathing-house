package com.breathinghouse.homeapi.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class GatewayHeartbeatRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GatewayHeartbeatRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean isReady() {
        try {
            jdbc.getJdbcTemplate().queryForList("SELECT 1 FROM home_api.gateway_heartbeat LIMIT 0");
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public void insert(GatewayHeartbeat heartbeat) {
        String sql = """
                INSERT INTO home_api.gateway_heartbeat (
                  gateway_id, device_id, reported_status, observed_at, received_at,
                  payload, kafka_topic, kafka_partition, kafka_offset
                ) VALUES (
                  :gatewayId, :deviceId, :reportedStatus, :observedAt, :receivedAt,
                  CAST(:payload AS json), :kafkaTopic, :kafkaPartition, :kafkaOffset
                )
                """;
        try {
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("gatewayId", heartbeat.gatewayId())
                    .addValue("deviceId", heartbeat.deviceId())
                    .addValue("reportedStatus", heartbeat.reportedStatus())
                    .addValue("observedAt", Timestamp.from(heartbeat.observedAt()))
                    .addValue("receivedAt", Timestamp.from(heartbeat.receivedAt()))
                    .addValue("payload", toJson(heartbeat.payload()))
                    .addValue("kafkaTopic", heartbeat.kafkaTopic())
                    .addValue("kafkaPartition", heartbeat.kafkaPartition())
                    .addValue("kafkaOffset", heartbeat.kafkaOffset()));
        } catch (DuplicateKeyException ignored) {
            // Idempotent replay of the same Kafka topic/partition/offset.
        }
    }

    public Optional<Instant> findLatestReceivedAt(String gatewayId) {
        String sql = """
                SELECT received_at
                FROM home_api.gateway_heartbeat
                WHERE gateway_id = :gatewayId
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """;
        return jdbc.query(
                        sql,
                        new MapSqlParameterSource("gatewayId", gatewayId),
                        (rs, rowNum) -> rs.getTimestamp("received_at").toInstant())
                .stream()
                .findFirst();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize heartbeat payload", ex);
        }
    }
}
