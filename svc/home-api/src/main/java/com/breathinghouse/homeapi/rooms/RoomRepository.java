package com.breathinghouse.homeapi.rooms;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RoomRepository {

    private record RoomRow(String roomId, String name, String description) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public RoomRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReady() {
        try {
            jdbc.getJdbcTemplate().queryForList("SELECT 1 FROM home_api.room LIMIT 0");
            jdbc.getJdbcTemplate().queryForList("SELECT 1 FROM home_api.sensor LIMIT 0");
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public List<RoomSummary> listRooms() {
        List<RoomRow> rows = jdbc.getJdbcTemplate().query(
                """
                SELECT room_id, name, description
                FROM home_api.room
                ORDER BY name, room_id
                """,
                (rs, rowNum) -> new RoomRow(
                        rs.getString("room_id"),
                        rs.getString("name"),
                        rs.getString("description")));

        Map<String, List<String>> sensorIdsByRoom = new HashMap<>();
        jdbc.getJdbcTemplate().query(
                """
                SELECT room_id, sensor_id
                FROM home_api.sensor
                WHERE room_id IS NOT NULL
                ORDER BY sensor_id
                """,
                rs -> {
                    sensorIdsByRoom
                            .computeIfAbsent(rs.getString("room_id"), key -> new ArrayList<>())
                            .add(rs.getString("sensor_id"));
                });

        return rows.stream()
                .map(row -> new RoomSummary(
                        row.roomId(),
                        row.name(),
                        row.description(),
                        sensorIdsByRoom.getOrDefault(row.roomId(), List.of())))
                .toList();
    }

    public Optional<RoomSummary> findById(String roomId) {
        MapSqlParameterSource params = new MapSqlParameterSource("roomId", roomId);
        return jdbc.query(
                        """
                        SELECT room_id, name, description
                        FROM home_api.room
                        WHERE room_id = :roomId
                        """,
                        params,
                        (rs, rowNum) -> new RoomRow(
                                rs.getString("room_id"),
                                rs.getString("name"),
                                rs.getString("description")))
                .stream()
                .findFirst()
                .map(row -> new RoomSummary(
                        row.roomId(),
                        row.name(),
                        row.description(),
                        sensorIdsOf(roomId)));
    }

    public boolean exists(String roomId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM home_api.room WHERE room_id = :roomId",
                new MapSqlParameterSource("roomId", roomId),
                Integer.class);
        return count != null && count > 0;
    }

    public void insert(String roomId, String name, String description, Instant now) {
        jdbc.update("""
                INSERT INTO home_api.room (room_id, name, description, created_at, updated_at)
                VALUES (:roomId, :name, :description, :now, :now)
                """,
                new MapSqlParameterSource()
                        .addValue("roomId", roomId)
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("now", Timestamp.from(now)));
    }

    public int update(String roomId, String name, String description, Instant now) {
        return jdbc.update("""
                UPDATE home_api.room
                SET name = :name, description = :description, updated_at = :now
                WHERE room_id = :roomId
                """,
                new MapSqlParameterSource()
                        .addValue("roomId", roomId)
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("now", Timestamp.from(now)));
    }

    /** Hard-delete. Sensors.room_id is SET NULL by FK. Returns rows deleted (0 or 1). */
    public int delete(String roomId) {
        return jdbc.update(
                "DELETE FROM home_api.room WHERE room_id = :roomId",
                new MapSqlParameterSource("roomId", roomId));
    }

    private List<String> sensorIdsOf(String roomId) {
        return jdbc.queryForList(
                """
                SELECT sensor_id
                FROM home_api.sensor
                WHERE room_id = :roomId
                ORDER BY sensor_id
                """,
                new MapSqlParameterSource("roomId", roomId),
                String.class);
    }
}
