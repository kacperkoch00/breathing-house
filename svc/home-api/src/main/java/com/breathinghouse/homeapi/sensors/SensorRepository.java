package com.breathinghouse.homeapi.sensors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SensorRepository {

    private record SensorRow(String sensorId, String displayName, String roomId) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public SensorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SensorSummary> listSensors() {
        List<SensorRow> rows = jdbc.getJdbcTemplate().query(
                """
                SELECT sensor_id, display_name, room_id
                FROM home_api.sensor
                ORDER BY sensor_id
                """,
                (rs, rowNum) -> new SensorRow(
                        rs.getString("sensor_id"),
                        rs.getString("display_name"),
                        rs.getString("room_id")));

        Map<String, List<String>> typesBySensor = new HashMap<>();
        jdbc.getJdbcTemplate().query(
                """
                SELECT DISTINCT COALESCE(sensor_id, device_id) AS sensor_id, sensor_type AS type
                FROM environment.environment_reading
                WHERE COALESCE(sensor_id, device_id) IS NOT NULL
                UNION
                SELECT DISTINCT COALESCE(sensor_id, device_id) AS sensor_id, event_type AS type
                FROM occupancy.occupancy_event
                WHERE COALESCE(sensor_id, device_id) IS NOT NULL
                """,
                rs -> {
                    typesBySensor
                            .computeIfAbsent(rs.getString("sensor_id"), key -> new ArrayList<>())
                            .add(rs.getString("type"));
                });

        return rows.stream()
                .map(row -> toSummary(row, typesBySensor.getOrDefault(row.sensorId(), List.of())))
                .toList();
    }

    public Optional<SensorSummary> findById(String sensorId) {
        return findRow(sensorId, false).map(row -> toSummary(row, typesOf(sensorId)));
    }

    /**
     * Reads the sensor's current room while holding a row lock until the surrounding transaction ends.
     * The outer Optional is empty when the sensor is unknown; the inner one when it is unassigned.
     */
    public Optional<Optional<String>> lockCurrentRoom(String sensorId) {
        return findRow(sensorId, true).map(row -> Optional.ofNullable(row.roomId()));
    }

    public int updateDisplayName(String sensorId, String displayName, Instant now) {
        return jdbc.update("""
                UPDATE home_api.sensor
                SET display_name = :displayName, updated_at = :now
                WHERE sensor_id = :sensorId
                """,
                new MapSqlParameterSource()
                        .addValue("sensorId", sensorId)
                        .addValue("displayName", displayName)
                        .addValue("now", Timestamp.from(now)));
    }

    public int updateRoom(String sensorId, String roomId, Instant now) {
        return jdbc.update("""
                UPDATE home_api.sensor
                SET room_id = :roomId, updated_at = :now
                WHERE sensor_id = :sensorId
                """,
                new MapSqlParameterSource()
                        .addValue("sensorId", sensorId)
                        .addValue("roomId", roomId)
                        .addValue("now", Timestamp.from(now)));
    }

    private Optional<SensorRow> findRow(String sensorId, boolean forUpdate) {
        String sql = """
                SELECT sensor_id, display_name, room_id
                FROM home_api.sensor
                WHERE sensor_id = :sensorId
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(
                        sql,
                        new MapSqlParameterSource("sensorId", sensorId),
                        (rs, rowNum) -> new SensorRow(
                                rs.getString("sensor_id"),
                                rs.getString("display_name"),
                                rs.getString("room_id")))
                .stream()
                .findFirst();
    }

    private List<String> typesOf(String sensorId) {
        return jdbc.queryForList(
                """
                SELECT sensor_type AS type
                FROM environment.environment_reading
                WHERE COALESCE(sensor_id, device_id) = :sensorId
                UNION
                SELECT event_type AS type
                FROM occupancy.occupancy_event
                WHERE COALESCE(sensor_id, device_id) = :sensorId
                """,
                new MapSqlParameterSource("sensorId", sensorId),
                String.class);
    }

    private static SensorSummary toSummary(SensorRow row, List<String> types) {
        List<String> sortedTypes = types.stream().distinct().sorted(Comparator.naturalOrder()).toList();
        return new SensorSummary(row.sensorId(), row.displayName(), sortedTypes, row.roomId());
    }
}
