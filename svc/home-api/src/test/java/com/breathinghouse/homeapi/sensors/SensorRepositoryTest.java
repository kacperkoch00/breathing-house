package com.breathinghouse.homeapi.sensors;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({SensorRepository.class, JdbcConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class SensorRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    @Autowired
    private SensorRepository repository;

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
    void listsDiscoveredSensorsWithTypesDerivedFromHistory() {
        insertRoom("room-a");
        insertSensor("hub-1", "Hub", "room-a");
        insertSensor("air-1", "air-1", null);
        insertSensor("silent-1", "silent-1", null);
        insertEnvironment("air-1", "AIR");
        insertEnvironment("air-1", "AIR");
        insertEnvironment("hub-1", "ROOM");
        insertOccupancy("hub-1", "PRESENCE");
        insertOccupancy("hub-1", "OPENING");

        assertThat(repository.listSensors()).containsExactly(
                new SensorSummary("air-1", "air-1", List.of("AIR"), null),
                new SensorSummary("hub-1", "Hub", List.of("OPENING", "PRESENCE", "ROOM"), "room-a"),
                new SensorSummary("silent-1", "silent-1", List.of(), null));
    }

    @Test
    void typesAreScopedToTheRequestedSensor() {
        insertSensor("legacy-1", "legacy-1", null);
        insertSensor("other-1", "other-1", null);
        insertEnvironment("legacy-1", "AIR");
        insertOccupancy("legacy-1", "OPENING");
        insertEnvironment("other-1", "ROOM");

        assertThat(repository.findById("legacy-1"))
                .get()
                .extracting(SensorSummary::types)
                .isEqualTo(List.of("AIR", "OPENING"));
        assertThat(repository.listSensors())
                .filteredOn(sensor -> sensor.sensorId().equals("legacy-1"))
                .singleElement()
                .extracting(SensorSummary::types)
                .isEqualTo(List.of("AIR", "OPENING"));
    }

    @Test
    void historyOnlySensorsAreNotDiscoveredUntilRegistered() {
        insertEnvironment("history-only", "AIR");

        assertThat(repository.listSensors()).isEmpty();
        assertThat(repository.findById("history-only")).isEmpty();
    }

    @Test
    void updateDisplayNameChangesNameAndUpdatedAtOnly() {
        insertRoom("room-a");
        insertSensor("air-1", "air-1", "room-a");

        assertThat(repository.updateDisplayName("air-1", "Kitchen", NOW)).isEqualTo(1);
        assertThat(repository.updateDisplayName("ghost", "Kitchen", NOW)).isZero();

        assertThat(repository.findById("air-1"))
                .contains(new SensorSummary("air-1", "Kitchen", List.of(), "room-a"));
        Timestamp updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM home_api.sensor WHERE sensor_id = 'air-1'", Timestamp.class);
        assertThat(updatedAt.toInstant()).isEqualTo(NOW);
    }

    @Test
    void duplicateDisplayNamesAreAllowed() {
        insertSensor("a", "Same", null);
        insertSensor("b", "Same", null);
        repository.updateDisplayName("a", "Other", NOW);
        repository.updateDisplayName("a", "Same", NOW);

        assertThat(repository.listSensors()).extracting(SensorSummary::displayName)
                .containsExactly("Same", "Same");
    }

    @Test
    void updateRoomAssignsAndUnassigns() {
        insertRoom("room-a");
        insertSensor("air-1", "air-1", null);

        repository.updateRoom("air-1", "room-a", NOW);
        assertThat(repository.findById("air-1").orElseThrow().roomId()).isEqualTo("room-a");

        repository.updateRoom("air-1", null, NOW);
        assertThat(repository.findById("air-1").orElseThrow().roomId()).isNull();
    }

    @Test
    void lockCurrentRoomDistinguishesUnknownUnassignedAndAssigned() {
        insertRoom("room-a");
        insertSensor("assigned", "assigned", "room-a");
        insertSensor("free", "free", null);

        assertThat(repository.lockCurrentRoom("ghost")).isEmpty();
        assertThat(repository.lockCurrentRoom("free")).contains(Optional.empty());
        assertThat(repository.lockCurrentRoom("assigned")).contains(Optional.of("room-a"));
    }

    private void insertRoom(String roomId) {
        jdbc.update("INSERT INTO home_api.room (room_id, name) VALUES (?, ?)", roomId, roomId);
    }

    private void insertSensor(String sensorId, String displayName, String roomId) {
        jdbc.update("""
                INSERT INTO home_api.sensor (sensor_id, display_name, room_id)
                VALUES (?, ?, ?)
                """, sensorId, displayName, roomId);
    }

    private void insertEnvironment(String sensorId, String sensorType) {
        jdbc.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_id, sensor_type, observed_at, received_at
                ) VALUES (NULL, ?, ?, ?, ?)
                """, sensorId, sensorType, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void insertOccupancy(String sensorId, String eventType) {
        jdbc.update("""
                INSERT INTO occupancy.occupancy_event (
                  room_id, sensor_id, event_type, observed_at, received_at
                ) VALUES (NULL, ?, ?, ?, ?)
                """, sensorId, eventType, Timestamp.from(NOW), Timestamp.from(NOW));
    }
}
