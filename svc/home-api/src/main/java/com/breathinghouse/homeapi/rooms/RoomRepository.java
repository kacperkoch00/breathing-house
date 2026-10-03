package com.breathinghouse.homeapi.rooms;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class RoomRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RoomRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReady() {
        try {
            jdbc.getJdbcTemplate().queryForList("SELECT 1 FROM home_api.room_metadata LIMIT 0");
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public List<RoomSummary> listRooms() {
        String sql = """
                SELECT rooms.room_id AS room_id,
                       COALESCE(metadata.display_name, rooms.room_id) AS display_name
                FROM (
                  SELECT room_id FROM environment.environment_reading
                  UNION
                  SELECT room_id FROM occupancy.occupancy_event
                ) rooms
                LEFT JOIN home_api.room_metadata metadata ON metadata.room_id = rooms.room_id
                ORDER BY rooms.room_id
                """;
        return jdbc.getJdbcTemplate().query(
                sql,
                (rs, rowNum) -> new RoomSummary(rs.getString("room_id"), rs.getString("display_name")));
    }

    public boolean existsInHistory(String roomId) {
        String sql = """
                SELECT (
                  EXISTS (SELECT 1 FROM environment.environment_reading WHERE room_id = :roomId)
                  OR EXISTS (SELECT 1 FROM occupancy.occupancy_event WHERE room_id = :roomId)
                )
                """;
        Boolean exists = jdbc.queryForObject(sql, new MapSqlParameterSource("roomId", roomId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public void upsertDisplayName(String roomId, String displayName, Instant updatedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("roomId", roomId)
                .addValue("displayName", displayName)
                .addValue("updatedAt", Timestamp.from(updatedAt));

        // Prefer PostgreSQL upsert; fall back for H2 test mode which lacks ON CONFLICT.
        try {
            jdbc.update("""
                    INSERT INTO home_api.room_metadata (room_id, display_name, updated_at)
                    VALUES (:roomId, :displayName, :updatedAt)
                    ON CONFLICT (room_id) DO UPDATE
                    SET display_name = EXCLUDED.display_name,
                        updated_at = EXCLUDED.updated_at
                    """, params);
        } catch (DataAccessException ex) {
            if (!isUnsupportedOnConflict(ex)) {
                throw ex;
            }
            upsertWithoutOnConflict(params);
        }
    }

    private void upsertWithoutOnConflict(MapSqlParameterSource params) {
        int updated = jdbc.update("""
                UPDATE home_api.room_metadata
                SET display_name = :displayName, updated_at = :updatedAt
                WHERE room_id = :roomId
                """, params);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO home_api.room_metadata (room_id, display_name, updated_at)
                    VALUES (:roomId, :displayName, :updatedAt)
                    """, params);
        } catch (DuplicateKeyException ex) {
            jdbc.update("""
                    UPDATE home_api.room_metadata
                    SET display_name = :displayName, updated_at = :updatedAt
                    WHERE room_id = :roomId
                    """, params);
        }
    }

    private static boolean isUnsupportedOnConflict(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause.getMessage();
        return message != null && message.contains("ON CONFLICT");
    }
}
