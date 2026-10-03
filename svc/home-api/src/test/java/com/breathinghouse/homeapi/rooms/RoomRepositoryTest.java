package com.breathinghouse.homeapi.rooms;

import com.breathinghouse.homeapi.config.JdbcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({RoomRepository.class, JdbcConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class RoomRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    @Autowired
    private RoomRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM home_api.sensor");
        jdbc.update("DELETE FROM home_api.room");
        jdbc.update("DELETE FROM environment.environment_reading");
        jdbc.update("DELETE FROM occupancy.occupancy_event");
    }

    @Test
    void listsRoomsFromRoomTableNotFromHistory() {
        insertHistoryRoom("history-only");
        repository.insert("empty-room", "Empty", null, NOW);

        assertThat(repository.listRooms())
                .containsExactly(new RoomSummary("empty-room", "Empty", null, List.of()));
    }

    @Test
    void listsRoomsWithCurrentSensorIdsOnly() {
        repository.insert("living", "Living Room", "Open plan", NOW);
        repository.insert("bedroom", "Bedroom", null, NOW);
        insertSensor("room-1", "living");
        insertSensor("air-1", "living");
        insertSensor("air-2", null);

        assertThat(repository.listRooms()).containsExactly(
                new RoomSummary("bedroom", "Bedroom", null, List.of()),
                new RoomSummary("living", "Living Room", "Open plan", List.of("air-1", "room-1")));
    }

    @Test
    void ordersByNameThenRoomId() {
        repository.insert("b", "Same", null, NOW);
        repository.insert("a", "Same", null, NOW);
        repository.insert("c", "Attic", null, NOW);

        assertThat(repository.listRooms())
                .extracting(RoomSummary::roomId)
                .containsExactly("c", "a", "b");
    }

    @Test
    void findByIdReturnsRoomWithSensors() {
        repository.insert("living", "Living Room", "Open plan", NOW);
        insertSensor("air-1", "living");

        assertThat(repository.findById("living"))
                .contains(new RoomSummary("living", "Living Room", "Open plan", List.of("air-1")));
        assertThat(repository.findById("missing")).isEmpty();
    }

    @Test
    void existsReflectsRoomTable() {
        repository.insert("living", "Living Room", null, NOW);
        insertHistoryRoom("history-only");

        assertThat(repository.exists("living")).isTrue();
        assertThat(repository.exists("history-only")).isFalse();
    }

    @Test
    void updatePersistsNameDescriptionAndUpdatedAt() {
        repository.insert("living", "Living Room", "Open plan", NOW);
        Instant later = NOW.plusSeconds(60);

        int updated = repository.update("living", "Salon", null, later);

        assertThat(updated).isEqualTo(1);
        assertThat(repository.findById("living"))
                .contains(new RoomSummary("living", "Salon", null, List.of()));
        Timestamp updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM home_api.room WHERE room_id = 'living'", Timestamp.class);
        Timestamp createdAt = jdbc.queryForObject(
                "SELECT created_at FROM home_api.room WHERE room_id = 'living'", Timestamp.class);
        assertThat(updatedAt.toInstant()).isEqualTo(later);
        assertThat(createdAt.toInstant()).isEqualTo(NOW);
        assertThat(repository.update("missing", "x", null, later)).isZero();
    }

    @Test
    void acceptsDuplicateNames() {
        repository.insert("a", "Shared Name", null, NOW);
        repository.insert("b", "Shared Name", null, NOW);

        assertThat(repository.listRooms()).extracting(RoomSummary::name)
                .containsExactly("Shared Name", "Shared Name");
    }

    @Test
    void readinessChecksRoomAndSensorTables() {
        assertThat(repository.isReady()).isTrue();
    }

    @Test
    void readinessFailsWhenSensorTableIsMissing() {
        jdbc.execute("ALTER TABLE home_api.sensor RENAME TO sensor_gone");
        try {
            assertThat(repository.isReady()).isFalse();
        } finally {
            jdbc.execute("ALTER TABLE home_api.sensor_gone RENAME TO sensor");
        }
    }

    private void insertSensor(String sensorId, String roomId) {
        jdbc.update("""
                INSERT INTO home_api.sensor (sensor_id, display_name, room_id)
                VALUES (?, ?, ?)
                """, sensorId, sensorId, roomId);
    }

    private void insertHistoryRoom(String roomId) {
        jdbc.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_type, co2, observed_at, received_at
                ) VALUES (?, 'AIR', 500, ?, ?)
                """, roomId, Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
