package com.breathinghouse.homeapi.sensors;

import com.breathinghouse.homeapi.alerts.AlertRepository;
import com.breathinghouse.homeapi.alerts.SensorReassignmentAlertHandler;
import com.breathinghouse.homeapi.config.ClockConfig;
import com.breathinghouse.homeapi.config.JdbcConfig;
import com.breathinghouse.homeapi.history.EnvironmentReading;
import com.breathinghouse.homeapi.history.HistoryRepository;
import com.breathinghouse.homeapi.rooms.RoomNotFoundException;
import com.breathinghouse.homeapi.rooms.RoomRepository;
import com.breathinghouse.homeapi.rooms.RoomService;
import com.breathinghouse.homeapi.rooms.RoomSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@JdbcTest
@Import({
        SensorService.class, SensorRepository.class, RoomRepository.class, RoomService.class,
        AlertRepository.class, HistoryRepository.class, JdbcConfig.class, ClockConfig.class
})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class SensorServiceIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-03T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-03T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T10:00:00Z");

    @Autowired
    private SensorService sensorService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private HistoryRepository historyRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SensorReassignmentAlertHandler alertHandler;

    @BeforeEach
    void clean() {
        deleteAll();
        jdbc.update("INSERT INTO home_api.room (room_id, name) VALUES ('room-a', 'Room A')");
        jdbc.update("INSERT INTO home_api.room (room_id, name) VALUES ('room-b', 'Room B')");
        jdbc.update("INSERT INTO home_api.sensor (sensor_id, display_name) VALUES ('air-1', 'air-1')");
    }

    @Test
    void firstAssignmentPlacesSensorWithoutTouchingAlerts() {
        SensorSummary assigned = sensorService.assign("room-a", "air-1");

        assertThat(assigned.roomId()).isEqualTo("room-a");
        assertThat(roomService.getRoom("room-a").sensorIds()).containsExactly("air-1");
        verifyNoInteractions(alertHandler);
    }

    @Test
    void moveIsAtomicSensorBelongsToExactlyOneRoom() {
        sensorService.assign("room-a", "air-1");

        SensorSummary moved = sensorService.assign("room-b", "air-1");

        assertThat(moved.roomId()).isEqualTo("room-b");
        assertThat(roomService.getRoom("room-a").sensorIds()).isEmpty();
        assertThat(roomService.getRoom("room-b").sensorIds()).containsExactly("air-1");
        verify(alertHandler).onSensorLeftRoom("air-1", "room-a", "room-b");
    }

    @Test
    void unassignClearsRoomAndRoomsNoLongerListTheSensor() {
        sensorService.assign("room-a", "air-1");

        SensorSummary unassigned = sensorService.unassign("room-a", "air-1");

        assertThat(unassigned.roomId()).isNull();
        assertThat(roomService.listRooms()).allSatisfy(room -> assertThat(room.sensorIds()).isEmpty());
        verify(alertHandler).onSensorLeftRoom("air-1", "room-a", null);
    }

    @Test
    void unassignFromAnotherRoomOrWhenUnassignedIsConflictAndChangesNothing() {
        sensorService.assign("room-a", "air-1");

        assertThatThrownBy(() -> sensorService.unassign("room-b", "air-1"))
                .isInstanceOf(SensorAssignmentConflictException.class);
        assertThat(sensorService.getSensor("air-1").roomId()).isEqualTo("room-a");

        sensorService.unassign("room-a", "air-1");
        assertThatThrownBy(() -> sensorService.unassign("room-a", "air-1"))
                .isInstanceOf(SensorAssignmentConflictException.class);
    }

    @Test
    void unknownRoomOrSensorIsNotFoundForBothOperations() {
        assertThatThrownBy(() -> sensorService.assign("ghost", "air-1"))
                .isInstanceOf(RoomNotFoundException.class);
        assertThatThrownBy(() -> sensorService.assign("room-a", "ghost"))
                .isInstanceOf(SensorNotFoundException.class);
        assertThatThrownBy(() -> sensorService.unassign("ghost", "air-1"))
                .isInstanceOf(RoomNotFoundException.class);
        assertThatThrownBy(() -> sensorService.unassign("room-a", "ghost"))
                .isInstanceOf(SensorNotFoundException.class);
    }

    @Test
    void renameDefaultsToSensorIdAndAllowsDuplicateNames() {
        jdbc.update("INSERT INTO home_api.sensor (sensor_id, display_name) VALUES ('air-2', 'air-2')");
        assertThat(sensorService.getSensor("air-1").displayName()).isEqualTo("air-1");

        sensorService.rename("air-1", "Kitchen air");
        sensorService.rename("air-2", "Kitchen air");

        assertThat(sensorService.listSensors()).extracting(SensorSummary::displayName)
                .containsExactly("Kitchen air", "Kitchen air");
        assertThat(sensorService.getSensor("air-1").sensorId()).isEqualTo("air-1");
    }

    @Test
    void renameUnknownSensorIsNotFound() {
        assertThatThrownBy(() -> sensorService.rename("ghost", "x"))
                .isInstanceOf(SensorNotFoundException.class);
    }

    @Test
    void sensorTypesComeFromHistoryAndHistoryStaysInSnapshottedRoomsAfterMove() {
        sensorService.assign("room-a", "air-1");
        insertReading("room-a", "air-1", 700.0, T1);
        insertEvent("room-a", "air-1", "PRESENCE", T1);

        sensorService.assign("room-b", "air-1");
        insertReading("room-b", "air-1", 800.0, T2);
        sensorService.unassign("room-b", "air-1");
        insertReading(null, "air-1", 900.0, T3);

        assertThat(sensorService.getSensor("air-1").types()).containsExactly("AIR", "PRESENCE");
        assertThat(co2In("room-a")).containsExactly(700.0);
        assertThat(co2In("room-b")).containsExactly(800.0);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void assignmentRollsBackWhenAlertReconciliationFails() {
        try {
            sensorService.assign("room-a", "air-1");
            doThrow(new IllegalStateException("alerts down")).when(alertHandler)
                    .onSensorLeftRoom(any(), any(), any());

            assertThatThrownBy(() -> sensorService.assign("room-b", "air-1"))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(sensorService.getSensor("air-1").roomId()).isEqualTo("room-a");
            List<RoomSummary> rooms = roomService.listRooms();
            assertThat(rooms).filteredOn(room -> room.roomId().equals("room-b"))
                    .singleElement()
                    .extracting(RoomSummary::sensorIds)
                    .isEqualTo(List.of());
        } finally {
            deleteAll();
        }
    }

    private List<Double> co2In(String roomId) {
        return historyRepository.findEnvironmentReadings(roomId, null, null, null, null, 100, 0)
                .items().stream().map(EnvironmentReading::co2).toList();
    }

    private void deleteAll() {
        jdbc.update("DELETE FROM home_api.sensor");
        jdbc.update("DELETE FROM home_api.room");
        jdbc.update("DELETE FROM environment.environment_reading");
        jdbc.update("DELETE FROM occupancy.occupancy_event");
    }

    private void insertReading(String roomId, String sensorId, double co2, Instant observedAt) {
        jdbc.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_id, sensor_type, co2, observed_at, received_at
                ) VALUES (?, ?, 'AIR', ?, ?, ?)
                """, roomId, sensorId, co2, Timestamp.from(observedAt), Timestamp.from(observedAt));
    }

    private void insertEvent(String roomId, String sensorId, String eventType, Instant observedAt) {
        jdbc.update("""
                INSERT INTO occupancy.occupancy_event (
                  room_id, sensor_id, event_type, present, observed_at, received_at
                ) VALUES (?, ?, ?, true, ?, ?)
                """, roomId, sensorId, eventType, Timestamp.from(observedAt), Timestamp.from(observedAt));
    }
}
