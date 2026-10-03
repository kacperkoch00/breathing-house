package com.breathinghouse.homeapi.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({HistoryRepository.class, com.breathinghouse.homeapi.config.JdbcConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class HistoryRepositoryTest {

    @Autowired
    private HistoryRepository historyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM environment.environment_reading");
        jdbcTemplate.update("DELETE FROM occupancy.occupancy_event");
    }

    @Test
    void environmentMappingAndOrderingAndHasMore() {
        insertEnvironment("living-room", "AIR", "2026-10-03T08:00:00Z", null, null, 700.0, null, null);
        insertEnvironment("living-room", "ROOM", "2026-10-03T09:00:00Z", 22.5, 45.0, null, null, null);
        insertEnvironment("living-room", "ROOM", "2026-10-03T10:00:00Z", 23.0, 46.0, null, 12.0, "BRIGHT");

        PageResponse<EnvironmentReading> page = historyRepository.findEnvironmentReadings(
                "living-room", null, null, null, 2, 0);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).sensorType()).isEqualTo(SensorType.ROOM);
        assertThat(page.items().get(0).observedAt()).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
        assertThat(page.items().get(0).light()).isEqualTo(12.0);
        assertThat(page.items().get(1).observedAt()).isEqualTo(Instant.parse("2026-10-03T09:00:00Z"));
        assertThat(page.items().get(1).co2()).isNull();
    }

    @Test
    void environmentOptionalFilters() {
        insertEnvironment("living-room", "AIR", "2026-10-03T08:00:00Z", null, null, 700.0, null, null);
        insertEnvironment("living-room", "ROOM", "2026-10-03T09:00:00Z", 22.5, 45.0, null, null, null);
        insertEnvironment("living-room", "AIR", "2026-10-03T11:00:00Z", null, null, 800.0, null, null);

        PageResponse<EnvironmentReading> page = historyRepository.findEnvironmentReadings(
                "living-room",
                SensorType.AIR,
                Instant.parse("2026-10-03T09:00:00Z"),
                Instant.parse("2026-10-03T12:00:00Z"),
                100,
                0);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().co2()).isEqualTo(800.0);
    }

    @Test
    void occupancyMappingPreservesFalseAndNull() {
        insertOccupancy("living-room", "PRESENCE", false, null, "2026-10-03T08:00:00Z");
        insertOccupancy("living-room", "OPENING", null, true, "2026-10-03T09:00:00Z");

        PageResponse<OccupancyEvent> page = historyRepository.findOccupancyEvents(
                "living-room", null, null, null, 100, 0);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).eventType()).isEqualTo(EventType.OPENING);
        assertThat(page.items().get(0).open()).isTrue();
        assertThat(page.items().get(0).present()).isNull();
        assertThat(page.items().get(1).present()).isFalse();
        assertThat(page.items().get(1).open()).isNull();
    }

    @Test
    void occupancyOptionalFiltersAndOffset() {
        insertOccupancy("living-room", "PRESENCE", true, null, "2026-10-03T08:00:00Z");
        insertOccupancy("living-room", "PRESENCE", false, null, "2026-10-03T09:00:00Z");
        insertOccupancy("living-room", "OPENING", null, false, "2026-10-03T10:00:00Z");

        PageResponse<OccupancyEvent> page = historyRepository.findOccupancyEvents(
                "living-room",
                EventType.PRESENCE,
                Instant.parse("2026-10-03T00:00:00Z"),
                Instant.parse("2026-10-03T23:59:59Z"),
                1,
                1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().present()).isTrue();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.offset()).isEqualTo(1);
    }

    @Test
    void mapsSensorIdAndKeepsNullWhenAbsent() {
        jdbcTemplate.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_id, sensor_type, co2, observed_at, received_at
                ) VALUES ('living-room', 'sensor-9', 'AIR', 600, ?, ?),
                         ('living-room', NULL, 'AIR', 700, ?, ?)
                """,
                Instant.parse("2026-10-03T09:00:00Z"), Instant.parse("2026-10-03T09:00:00Z"),
                Instant.parse("2026-10-03T10:00:00Z"), Instant.parse("2026-10-03T10:00:00Z"));

        PageResponse<EnvironmentReading> page = historyRepository.findEnvironmentReadings(
                "living-room", null, null, null, 100, 0);

        assertThat(page.items()).extracting(EnvironmentReading::sensorId)
                .containsExactly(null, "sensor-9");
    }

    @Test
    void occupancyMapsSensorId() {
        jdbcTemplate.update("""
                INSERT INTO occupancy.occupancy_event (
                  room_id, sensor_id, event_type, present, observed_at, received_at
                ) VALUES ('kitchen', 'door-1', 'PRESENCE', true, ?, ?)
                """, Instant.parse("2026-10-03T08:00:00Z"), Instant.parse("2026-10-03T08:00:00Z"));

        PageResponse<OccupancyEvent> page = historyRepository.findOccupancyEvents(
                "kitchen", null, null, null, 100, 0);

        assertThat(page.items()).extracting(OccupancyEvent::sensorId).containsExactly("door-1");
    }

    @Test
    void readingsStayUnderTheirSnapshottedRoomAndUnassignedRowsAreOmitted() {
        insertEnvironment("old-room", "AIR", "2026-10-03T08:00:00Z", null, null, 700.0, null, null);
        insertEnvironment("new-room", "AIR", "2026-10-03T09:00:00Z", null, null, 800.0, null, null);
        insertEnvironment(null, "AIR", "2026-10-03T10:00:00Z", null, null, 900.0, null, null);
        insertOccupancy("old-room", "PRESENCE", true, null, "2026-10-03T08:00:00Z");
        insertOccupancy("new-room", "PRESENCE", false, null, "2026-10-03T09:00:00Z");
        insertOccupancy(null, "PRESENCE", true, null, "2026-10-03T10:00:00Z");

        assertThat(historyRepository.findEnvironmentReadings("old-room", null, null, null, 100, 0).items())
                .extracting(EnvironmentReading::co2).containsExactly(700.0);
        assertThat(historyRepository.findEnvironmentReadings("new-room", null, null, null, 100, 0).items())
                .extracting(EnvironmentReading::co2).containsExactly(800.0);
        assertThat(historyRepository.findOccupancyEvents("old-room", null, null, null, 100, 0).items())
                .extracting(OccupancyEvent::present).containsExactly(true);
        assertThat(historyRepository.findOccupancyEvents("new-room", null, null, null, 100, 0).items())
                .extracting(OccupancyEvent::present).containsExactly(false);
    }

    @Test
    void isReadyWhenTablesExist() {
        assertThat(historyRepository.isReady()).isTrue();
    }

    private void insertEnvironment(
            String roomId,
            String sensorType,
            String observedAt,
            Double temperature,
            Double humidity,
            Double co2,
            Double light,
            String lightLevel) {
        jdbcTemplate.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_id, sensor_type, temperature, humidity, co2, light, light_level,
                  observed_at, received_at, ingested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                roomId,
                "sensor-1",
                sensorType,
                temperature,
                humidity,
                co2,
                light,
                lightLevel,
                Instant.parse(observedAt),
                Instant.parse(observedAt).plusSeconds(1),
                Instant.parse(observedAt).plusSeconds(2));
    }

    private void insertOccupancy(
            String roomId,
            String eventType,
            Boolean present,
            Boolean open,
            String observedAt) {
        jdbcTemplate.update("""
                INSERT INTO occupancy.occupancy_event (
                  room_id, sensor_id, event_type, present, open,
                  observed_at, received_at, ingested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                roomId,
                "sensor-1",
                eventType,
                present,
                open,
                Instant.parse(observedAt),
                Instant.parse(observedAt).plusSeconds(1),
                Instant.parse(observedAt).plusSeconds(2));
    }
}
