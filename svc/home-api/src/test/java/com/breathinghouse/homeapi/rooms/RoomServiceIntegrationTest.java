package com.breathinghouse.homeapi.rooms;

import com.breathinghouse.homeapi.alerts.AlertRepository;
import com.breathinghouse.homeapi.config.ClockConfig;
import com.breathinghouse.homeapi.config.JdbcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Import({RoomRepository.class, RoomService.class, AlertRepository.class, JdbcConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class RoomServiceIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM home_api.alert");
        jdbc.update("DELETE FROM home_api.alert_state");
        jdbc.update("DELETE FROM home_api.sensor");
        jdbc.update("DELETE FROM home_api.room");
    }

    @Test
    void createPersistsRoomWithServerGeneratedUuid() {
        RoomSummary created = roomService.createRoom("Living Room", "Open plan");

        assertThat(UUID.fromString(created.roomId())).isNotNull();
        assertThat(created.name()).isEqualTo("Living Room");
        assertThat(created.description()).isEqualTo("Open plan");
        assertThat(created.sensorIds()).isEmpty();
        assertThat(roomService.getRoom(created.roomId())).isEqualTo(created);
    }

    @Test
    void duplicateNamesGetDistinctRoomIds() {
        RoomSummary first = roomService.createRoom("Office", null);
        RoomSummary second = roomService.createRoom("Office", null);

        assertThat(first.roomId()).isNotEqualTo(second.roomId());
        assertThat(roomService.listRooms()).hasSize(2);
    }

    @Test
    void createListGetPatchRoundTrip() {
        RoomSummary created = roomService.createRoom("Living Room", "Open plan");
        jdbc.update("INSERT INTO home_api.sensor (sensor_id, display_name, room_id) VALUES ('air-1', 'air-1', ?)",
                created.roomId());

        RoomSummary renamed = roomService.updateRoom(created.roomId(), new RoomPatch("Salon", false, null));
        assertThat(renamed.name()).isEqualTo("Salon");
        assertThat(renamed.description()).isEqualTo("Open plan");
        assertThat(renamed.sensorIds()).containsExactly("air-1");

        RoomSummary cleared = roomService.updateRoom(created.roomId(), new RoomPatch(null, true, null));
        assertThat(cleared.name()).isEqualTo("Salon");
        assertThat(cleared.description()).isNull();

        assertThat(roomService.listRooms()).containsExactly(cleared);
    }

    @Test
    void patchingUnknownRoomDoesNotCreateIt() {
        assertThatThrownBy(() -> roomService.updateRoom("ghost", new RoomPatch("Ghost", false, null)))
                .isInstanceOf(RoomNotFoundException.class);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM home_api.room", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void deleteRemovesRoomAndUnassignsSensors() {
        RoomSummary created = roomService.createRoom("Spare", null);
        jdbc.update(
                "INSERT INTO home_api.sensor (sensor_id, display_name, room_id) VALUES ('air-1', 'air-1', ?)",
                created.roomId());
        jdbc.update("""
                INSERT INTO home_api.alert (
                  rule_id, room_id, sensor_id, severity, status, message, triggered_at,
                  last_evaluated_at, rule_snapshot
                ) VALUES ('air-data-stale', ?, 'air-1', 'INFO', 'ACTIVE', 'stale', NOW(), NOW(), '{}')
                """, created.roomId());

        roomService.deleteRoom(created.roomId());

        assertThatThrownBy(() -> roomService.getRoom(created.roomId()))
                .isInstanceOf(RoomNotFoundException.class);
        assertThat(jdbc.queryForObject(
                "SELECT room_id FROM home_api.sensor WHERE sensor_id = 'air-1'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM home_api.alert WHERE room_id = ?", String.class, created.roomId()))
                .isEqualTo("RESOLVED");
    }
}
