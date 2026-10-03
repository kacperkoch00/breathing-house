package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.history.EventType;
import com.breathinghouse.homeapi.history.SensorType;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class AlertRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AlertRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReady() {
        try {
            jdbc.getJdbcTemplate().queryForList("SELECT 1 FROM home_api.alert LIMIT 0");
            jdbc.getJdbcTemplate().queryForList("SELECT 1 FROM home_api.alert_state LIMIT 0");
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public List<EnvironmentSnapshot> latestEnvironment(SensorType sensorType) {
        String sql = """
                SELECT room_id, device_id, sensor_type, temperature, humidity, co2, light, observed_at
                FROM (
                  SELECT room_id, device_id, sensor_type, temperature, humidity, co2, light, observed_at,
                         ROW_NUMBER() OVER (
                           PARTITION BY room_id, sensor_type, COALESCE(device_id, '')
                           ORDER BY observed_at DESC, id DESC
                         ) AS row_number
                  FROM environment.environment_reading
                  WHERE sensor_type = :sensorType
                ) latest
                WHERE row_number = 1
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource("sensorType", sensorType.name()),
                (rs, rowNum) -> new EnvironmentSnapshot(
                        rs.getString("room_id"),
                        rs.getString("device_id"),
                        SensorType.valueOf(rs.getString("sensor_type")),
                        nullableDouble(rs.getObject("temperature")),
                        nullableDouble(rs.getObject("humidity")),
                        nullableDouble(rs.getObject("co2")),
                        nullableDouble(rs.getObject("light")),
                        rs.getTimestamp("observed_at").toInstant()));
    }

    public List<OccupancySnapshot> latestOccupancy(EventType eventType) {
        String sql = """
                SELECT room_id, device_id, event_type, present, open, observed_at
                FROM (
                  SELECT room_id, device_id, event_type, present, open, observed_at,
                         ROW_NUMBER() OVER (
                           PARTITION BY room_id, event_type, COALESCE(device_id, '')
                           ORDER BY observed_at DESC, id DESC
                         ) AS row_number
                  FROM occupancy.occupancy_event
                  WHERE event_type = :eventType
                ) latest
                WHERE row_number = 1
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource("eventType", eventType.name()),
                (rs, rowNum) -> new OccupancySnapshot(
                        rs.getString("room_id"),
                        rs.getString("device_id"),
                        EventType.valueOf(rs.getString("event_type")),
                        nullableBoolean(rs.getObject("present")),
                        nullableBoolean(rs.getObject("open")),
                        rs.getTimestamp("observed_at").toInstant()));
    }

    public Optional<AlertState> findState(String ruleId, String roomId, String deviceId) {
        String sql = """
                SELECT rule_id, room_id, device_id, condition_active, condition_started_at,
                       last_value, rule_fingerprint, last_evaluated_at
                FROM home_api.alert_state
                WHERE rule_id = :ruleId AND room_id = :roomId AND device_key = :deviceKey
                """;
        MapSqlParameterSource params = instanceParams(ruleId, roomId, deviceId);
        return jdbc.query(sql, params, (rs, rowNum) -> new AlertState(
                        rs.getString("rule_id"),
                        rs.getString("room_id"),
                        rs.getString("device_id"),
                        rs.getBoolean("condition_active"),
                        nullableInstant(rs.getTimestamp("condition_started_at")),
                        rs.getString("last_value"),
                        rs.getString("rule_fingerprint"),
                        rs.getTimestamp("last_evaluated_at").toInstant()))
                .stream()
                .findFirst();
    }

    public void saveState(AlertState state) {
        String update = """
                UPDATE home_api.alert_state
                SET device_id = :deviceId,
                    condition_active = :conditionActive,
                    condition_started_at = :conditionStartedAt,
                    last_value = :lastValue,
                    rule_fingerprint = :ruleFingerprint,
                    last_evaluated_at = :lastEvaluatedAt
                WHERE rule_id = :ruleId AND room_id = :roomId AND device_key = :deviceKey
                """;
        MapSqlParameterSource params = stateParams(state);
        if (jdbc.update(update, params) > 0) {
            return;
        }

        String insert = """
                INSERT INTO home_api.alert_state (
                  rule_id, room_id, device_key, device_id, condition_active,
                  condition_started_at, last_value, rule_fingerprint, last_evaluated_at
                ) VALUES (
                  :ruleId, :roomId, :deviceKey, :deviceId, :conditionActive,
                  :conditionStartedAt, :lastValue, :ruleFingerprint, :lastEvaluatedAt
                )
                """;
        try {
            jdbc.update(insert, params);
        } catch (DuplicateKeyException ex) {
            jdbc.update(update, params);
        }
    }

    public boolean hasActiveAlert(String ruleId, String roomId, String deviceId) {
        String sql = """
                SELECT COUNT(*)
                FROM home_api.alert
                WHERE rule_id = :ruleId
                  AND room_id = :roomId
                  AND COALESCE(device_id, '') = :deviceKey
                  AND status = 'ACTIVE'
                """;
        Long count = jdbc.queryForObject(sql, instanceParams(ruleId, roomId, deviceId), Long.class);
        return count != null && count > 0;
    }

    public void createAlert(
            AlertConfiguration.ValidatedRule rule,
            String roomId,
            String deviceId,
            String message,
            String triggerValue,
            Instant now,
            String ruleSnapshot) {
        String sql = """
                INSERT INTO home_api.alert (
                  rule_id, room_id, device_id, severity, status, message, trigger_value,
                  triggered_at, last_evaluated_at, rule_snapshot
                ) VALUES (
                  :ruleId, :roomId, :deviceId, :severity, 'ACTIVE', :message, :triggerValue,
                  :now, :now, CAST(:ruleSnapshot AS jsonb)
                )
                """;
        try {
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("ruleId", rule.id())
                    .addValue("roomId", roomId)
                    .addValue("deviceId", deviceId)
                    .addValue("severity", rule.severity().name())
                    .addValue("message", message)
                    .addValue("triggerValue", triggerValue)
                    .addValue("now", Timestamp.from(now))
                    .addValue("ruleSnapshot", ruleSnapshot));
        } catch (DuplicateKeyException ignored) {
            // Another replica activated the same instance between our read and insert.
        }
    }

    public void touchActiveAlert(String ruleId, String roomId, String deviceId, Instant now) {
        String sql = """
                UPDATE home_api.alert
                SET last_evaluated_at = :now
                WHERE rule_id = :ruleId
                  AND room_id = :roomId
                  AND COALESCE(device_id, '') = :deviceKey
                  AND status = 'ACTIVE'
                """;
        MapSqlParameterSource params = instanceParams(ruleId, roomId, deviceId)
                .addValue("now", Timestamp.from(now));
        jdbc.update(sql, params);
    }

    public void resolveActiveAlert(String ruleId, String roomId, String deviceId, Instant now) {
        String sql = """
                UPDATE home_api.alert
                SET status = 'RESOLVED', resolved_at = :now, last_evaluated_at = :now
                WHERE rule_id = :ruleId
                  AND room_id = :roomId
                  AND COALESCE(device_id, '') = :deviceKey
                  AND status = 'ACTIVE'
                """;
        MapSqlParameterSource params = instanceParams(ruleId, roomId, deviceId)
                .addValue("now", Timestamp.from(now));
        jdbc.update(sql, params);
    }

    public void resolveAlertsForInactiveRules(Collection<String> activeRuleIds, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource("now", Timestamp.from(now));
        String filter = "";
        if (!activeRuleIds.isEmpty()) {
            filter = " AND rule_id NOT IN (:activeRuleIds)";
            params.addValue("activeRuleIds", activeRuleIds);
        }
        jdbc.update("""
                UPDATE home_api.alert
                SET status = 'RESOLVED', resolved_at = :now, last_evaluated_at = :now
                WHERE status = 'ACTIVE'
                """ + filter, params);

        jdbc.update("""
                UPDATE home_api.alert_state
                SET condition_active = false,
                    condition_started_at = NULL,
                    last_evaluated_at = :now
                WHERE condition_active = true
                """ + filter, params);
    }

    public void resolveAlertsOutsideRooms(String ruleId, Collection<String> rooms, Instant now) {
        if (rooms.contains("*")) {
            return;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ruleId", ruleId)
                .addValue("rooms", rooms)
                .addValue("now", Timestamp.from(now));
        String roomFilter = rooms.isEmpty() ? "" : " AND room_id NOT IN (:rooms)";

        jdbc.update("""
                UPDATE home_api.alert
                SET status = 'RESOLVED', resolved_at = :now, last_evaluated_at = :now
                WHERE status = 'ACTIVE' AND rule_id = :ruleId
                """ + roomFilter, params);
        jdbc.update("""
                UPDATE home_api.alert_state
                SET condition_active = false,
                    condition_started_at = NULL,
                    last_evaluated_at = :now
                WHERE condition_active = true AND rule_id = :ruleId
                """ + roomFilter, params);
    }

    private static MapSqlParameterSource instanceParams(String ruleId, String roomId, String deviceId) {
        return new MapSqlParameterSource()
                .addValue("ruleId", ruleId)
                .addValue("roomId", roomId)
                .addValue("deviceId", deviceId)
                .addValue("deviceKey", deviceId == null ? "" : deviceId);
    }

    private static MapSqlParameterSource stateParams(AlertState state) {
        return instanceParams(state.ruleId(), state.roomId(), state.deviceId())
                .addValue("conditionActive", state.conditionActive())
                .addValue(
                        "conditionStartedAt",
                        state.conditionStartedAt() == null ? null : Timestamp.from(state.conditionStartedAt()))
                .addValue("lastValue", state.lastValue())
                .addValue("ruleFingerprint", state.ruleFingerprint())
                .addValue("lastEvaluatedAt", Timestamp.from(state.lastEvaluatedAt()));
    }

    private static Double nullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static Boolean nullableBoolean(Object value) {
        return value == null ? null : (Boolean) value;
    }

    private static Instant nullableInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record EnvironmentSnapshot(
            String roomId,
            String deviceId,
            SensorType sensorType,
            Double temperature,
            Double humidity,
            Double co2,
            Double light,
            Instant observedAt) {
    }

    public record OccupancySnapshot(
            String roomId,
            String deviceId,
            EventType eventType,
            Boolean present,
            Boolean open,
            Instant observedAt) {
    }

    public record AlertState(
            String ruleId,
            String roomId,
            String deviceId,
            boolean conditionActive,
            Instant conditionStartedAt,
            String lastValue,
            String ruleFingerprint,
            Instant lastEvaluatedAt) {
    }
}
