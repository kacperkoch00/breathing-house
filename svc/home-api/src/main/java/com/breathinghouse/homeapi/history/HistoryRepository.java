package com.breathinghouse.homeapi.history;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class HistoryRepository {

    private static final RowMapper<EnvironmentReading> ENVIRONMENT_READING_MAPPER = (rs, rowNum) ->
            new EnvironmentReading(
                    rs.getLong("id"),
                    rs.getString("room_id"),
                    rs.getString("sensor_id"),
                    SensorType.valueOf(rs.getString("sensor_type")),
                    getNullableDouble(rs, "temperature"),
                    getNullableDouble(rs, "humidity"),
                    getNullableDouble(rs, "co2"),
                    getNullableDouble(rs, "light"),
                    rs.getString("light_level"),
                    requireInstant(rs, "observed_at"),
                    requireInstant(rs, "received_at"),
                    requireInstant(rs, "ingested_at")
            );

    private static final RowMapper<OccupancyEvent> OCCUPANCY_EVENT_MAPPER = (rs, rowNum) ->
            new OccupancyEvent(
                    rs.getLong("id"),
                    rs.getString("room_id"),
                    rs.getString("sensor_id"),
                    EventType.valueOf(rs.getString("event_type")),
                    getNullableBoolean(rs, "present"),
                    getNullableBoolean(rs, "open"),
                    requireInstant(rs, "observed_at"),
                    requireInstant(rs, "received_at"),
                    requireInstant(rs, "ingested_at")
            );

    private final NamedParameterJdbcTemplate jdbc;

    public HistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReady() {
        try {
            jdbc.getJdbcTemplate().queryForList(
                    "SELECT 1 FROM environment.environment_reading LIMIT 0");
            jdbc.getJdbcTemplate().queryForList(
                    "SELECT 1 FROM occupancy.occupancy_event LIMIT 0");
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public PageResponse<EnvironmentReading> findEnvironmentReadings(
            String roomId,
            SensorType sensorType,
            Instant from,
            Instant to,
            int limit,
            int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, room_id, sensor_id, sensor_type, temperature, humidity, co2, light,
                       light_level, observed_at, received_at, ingested_at
                FROM environment.environment_reading
                WHERE room_id = :roomId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("roomId", roomId);
        appendOptionalFilters(sql, params, sensorType == null ? null : sensorType.name(), "sensor_type", from, to);
        sql.append(" ORDER BY observed_at DESC, id DESC LIMIT :fetchLimit OFFSET :offset");
        params.addValue("fetchLimit", limit + 1);
        params.addValue("offset", offset);

        List<EnvironmentReading> rows = jdbc.query(sql.toString(), params, ENVIRONMENT_READING_MAPPER);
        return toPage(rows, limit, offset);
    }

    public PageResponse<OccupancyEvent> findOccupancyEvents(
            String roomId,
            EventType eventType,
            Instant from,
            Instant to,
            int limit,
            int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, room_id, sensor_id, event_type, present, open,
                       observed_at, received_at, ingested_at
                FROM occupancy.occupancy_event
                WHERE room_id = :roomId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("roomId", roomId);
        appendOptionalFilters(sql, params, eventType == null ? null : eventType.name(), "event_type", from, to);
        sql.append(" ORDER BY observed_at DESC, id DESC LIMIT :fetchLimit OFFSET :offset");
        params.addValue("fetchLimit", limit + 1);
        params.addValue("offset", offset);

        List<OccupancyEvent> rows = jdbc.query(sql.toString(), params, OCCUPANCY_EVENT_MAPPER);
        return toPage(rows, limit, offset);
    }

    private static void appendOptionalFilters(
            StringBuilder sql,
            MapSqlParameterSource params,
            String typeValue,
            String typeColumn,
            Instant from,
            Instant to) {
        if (typeValue != null) {
            sql.append(" AND ").append(typeColumn).append(" = :typeValue");
            params.addValue("typeValue", typeValue);
        }
        if (from != null) {
            sql.append(" AND observed_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND observed_at <= :to");
            params.addValue("to", Timestamp.from(to));
        }
    }

    private static <T> PageResponse<T> toPage(List<T> rows, int limit, int offset) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
        return new PageResponse<>(items, limit, offset, hasMore);
    }

    private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean getNullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant requireInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        if (timestamp == null) {
            throw new SQLException("Column " + column + " must not be null");
        }
        return timestamp.toInstant();
    }
}
