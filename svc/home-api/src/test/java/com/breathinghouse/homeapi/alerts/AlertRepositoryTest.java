package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertRepository.AlertState;
import com.breathinghouse.homeapi.history.EventType;
import com.breathinghouse.homeapi.history.SensorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({AlertRepository.class, com.breathinghouse.homeapi.config.JdbcConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class AlertRepositoryTest {

    @Autowired
    private AlertRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM home_api.alert_state");
        jdbc.update("DELETE FROM home_api.alert");
        jdbc.update("DELETE FROM environment.environment_reading");
        jdbc.update("DELETE FROM occupancy.occupancy_event");
        jdbc.update("DELETE FROM home_api.sensor");
        jdbc.update("DELETE FROM home_api.room");
    }

    @Test
    void returnsOnlyLatestEnvironmentReadingPerSensor() {
        assign("living-room", "air-1");
        assign("bedroom", "air-2");
        insertEnvironment("living-room", "air-1", "AIR", 700.0, "2026-10-03T08:00:00Z");
        insertEnvironment("living-room", "air-1", "AIR", 1600.0, "2026-10-03T09:00:00Z");
        insertEnvironment("bedroom", "air-2", "AIR", 800.0, "2026-10-03T10:00:00Z");

        var snapshots = repository.latestEnvironment(SensorType.AIR);

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots)
                .filteredOn(snapshot -> snapshot.roomId().equals("living-room"))
                .singleElement()
                .extracting(AlertRepository.EnvironmentSnapshot::co2)
                .isEqualTo(1600.0);
    }

    @Test
    void returnsOnlyLatestOccupancyEventPerSensor() {
        assign("kitchen", "window-1");
        insertOccupancy("kitchen", "window-1", false, "2026-10-03T08:00:00Z");
        insertOccupancy("kitchen", "window-1", true, "2026-10-03T09:00:00Z");

        var snapshots = repository.latestOccupancy(EventType.OPENING);

        assertThat(snapshots).singleElement()
                .extracting(AlertRepository.OccupancySnapshot::open)
                .isEqualTo(true);
    }

    @Test
    void savesAndUpdatesPersistentConditionState() {
        Instant first = Instant.parse("2026-10-03T09:00:00Z");
        repository.saveState(new AlertState(
                "high-co2", "living-room", null, true, first, "1600", "rule-v1", first));
        repository.saveState(new AlertState(
                "high-co2", "living-room", null, false, null, "700", "rule-v2", first.plusSeconds(10)));

        AlertState state = repository.findState("high-co2", "living-room", null).orElseThrow();
        assertThat(state.conditionActive()).isFalse();
        assertThat(state.conditionStartedAt()).isNull();
        assertThat(state.lastValue()).isEqualTo("700");
        assertThat(state.ruleFingerprint()).isEqualTo("rule-v2");
    }

    @Test
    void snapshotsExposeSensorIdFromSensorIdOrLegacyDeviceId() {
        assign("living-room", "air-1");
        assign("living-room", "air-legacy");
        insertEnvironmentRaw("living-room", null, "air-legacy", "2026-10-03T09:00:00Z");
        insertEnvironmentRaw("living-room", "air-1", "stale-device", "2026-10-03T09:00:00Z");

        assertThat(repository.latestEnvironment(SensorType.AIR))
                .extracting(AlertRepository.EnvironmentSnapshot::sensorId)
                .containsExactlyInAnyOrder("air-1", "air-legacy");
    }

    @Test
    void unassignedSensorReadingsAreExcluded() {
        assign(null, "air-1");
        insertEnvironment("living-room", "air-1", "AIR", 1600.0, "2026-10-03T09:00:00Z");
        insertEnvironmentRaw(null, "air-1", null, "2026-10-03T10:00:00Z");
        insertOccupancy("kitchen", "window-1", true, "2026-10-03T09:00:00Z");

        assertThat(repository.latestEnvironment(SensorType.AIR)).isEmpty();
        assertThat(repository.latestOccupancy(EventType.OPENING)).isEmpty();
    }

    @Test
    void onlyReadingsMatchingTheCurrentAssignmentAreReturned() {
        assign("new-room", "air-1");
        insertEnvironment("old-room", "air-1", "AIR", 1600.0, "2026-10-03T09:00:00Z");

        assertThat(repository.latestEnvironment(SensorType.AIR)).isEmpty();

        insertEnvironment("new-room", "air-1", "AIR", 900.0, "2026-10-03T09:30:00Z");

        assertThat(repository.latestEnvironment(SensorType.AIR))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.roomId()).isEqualTo("new-room");
                    assertThat(snapshot.co2()).isEqualTo(900.0);
                });
    }

    @Test
    void staleReadingFromPreviousRoomDoesNotComeBackBehindANewerOne() {
        assign("new-room", "air-1");
        insertEnvironment("new-room", "air-1", "AIR", 800.0, "2026-10-03T08:00:00Z");
        insertEnvironment("old-room", "air-1", "AIR", 1600.0, "2026-10-03T09:00:00Z");

        assertThat(repository.latestEnvironment(SensorType.AIR)).isEmpty();
    }

    @Test
    void legacyRowsWithoutSensorIdentityKeepUsingTheirRoom() {
        insertEnvironment("living-room", null, "AIR", 700.0, "2026-10-03T09:00:00Z");
        insertEnvironment("bedroom", null, "AIR", 800.0, "2026-10-03T09:00:00Z");

        assertThat(repository.latestEnvironment(SensorType.AIR))
                .extracting(AlertRepository.EnvironmentSnapshot::roomId)
                .containsExactlyInAnyOrder("living-room", "bedroom");
    }

    @Test
    void resolveSensorAlertsInRoomResolvesOnlyThatSensorsAlertsAndState() {
        Instant now = Instant.parse("2026-10-03T10:00:00Z");
        insertActiveAlert("high-co2", "old-room", "air-1");
        insertActiveAlert("high-co2", "old-room", "air-2");
        insertActiveAlert("high-co2", "other-room", "air-1");
        insertActiveAlert("poor-air", "old-room", null);
        repository.saveState(new AlertState("high-co2", "old-room", "air-1", true, now, "1600", "fp", now));
        repository.saveState(new AlertState("high-co2", "old-room", "air-2", true, now, "1600", "fp", now));

        repository.resolveSensorAlertsInRoom("old-room", "air-1", now.plusSeconds(5));

        assertThat(statusOf("high-co2", "old-room", "air-1")).isEqualTo("RESOLVED");
        assertThat(statusOf("high-co2", "old-room", "air-2")).isEqualTo("ACTIVE");
        assertThat(statusOf("high-co2", "other-room", "air-1")).isEqualTo("ACTIVE");
        assertThat(statusOf("poor-air", "old-room", null)).isEqualTo("ACTIVE");
        assertThat(repository.findState("high-co2", "old-room", "air-1").orElseThrow().conditionActive())
                .isFalse();
        assertThat(repository.findState("high-co2", "old-room", "air-2").orElseThrow().conditionActive())
                .isTrue();
    }

    @Test
    void readinessIncludesAlertTables() {
        assertThat(repository.isReady()).isTrue();
    }

    private void assign(String roomId, String sensorId) {
        if (roomId != null) {
            jdbc.update("""
                    INSERT INTO home_api.room (room_id, name)
                    SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM home_api.room WHERE room_id = ?)
                    """, roomId, roomId, roomId);
        }
        jdbc.update("""
                INSERT INTO home_api.sensor (sensor_id, display_name, room_id)
                VALUES (?, ?, ?)
                """, sensorId, sensorId, roomId);
    }

    private void insertActiveAlert(String ruleId, String roomId, String sensorId) {
        Instant now = Instant.parse("2026-10-03T09:00:00Z");
        jdbc.update("""
                INSERT INTO home_api.alert (
                  rule_id, room_id, device_id, severity, status, message, triggered_at,
                  last_evaluated_at, rule_snapshot
                ) VALUES (?, ?, ?, 'WARNING', 'ACTIVE', 'm', ?, ?, '{}')
                """, ruleId, roomId, sensorId, Timestamp.from(now), Timestamp.from(now));
    }

    private String statusOf(String ruleId, String roomId, String sensorId) {
        return jdbc.queryForObject("""
                SELECT status FROM home_api.alert
                WHERE rule_id = ? AND room_id = ? AND COALESCE(device_id, '') = ?
                """, String.class, ruleId, roomId, sensorId == null ? "" : sensorId);
    }

    private void insertEnvironmentRaw(
            String roomId,
            String sensorId,
            String deviceId,
            String observedAt) {
        Instant observed = Instant.parse(observedAt);
        jdbc.update("""
                        INSERT INTO environment.environment_reading (
                          room_id, sensor_id, device_id, sensor_type, co2, observed_at, received_at
                        ) VALUES (?, ?, ?, 'AIR', 500, ?, ?)
                        """,
                roomId,
                sensorId,
                deviceId,
                Timestamp.from(observed),
                Timestamp.from(observed));
    }

    private void insertEnvironment(
            String roomId,
            String sensorId,
            String sensorType,
            double co2,
            String observedAt) {
        Instant observed = Instant.parse(observedAt);
        jdbc.update("""
                        INSERT INTO environment.environment_reading (
                          room_id, sensor_id, sensor_type, co2, observed_at, received_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                roomId,
                sensorId,
                sensorType,
                co2,
                Timestamp.from(observed),
                Timestamp.from(observed));
    }

    private void insertOccupancy(
            String roomId,
            String sensorId,
            boolean open,
            String observedAt) {
        Instant observed = Instant.parse(observedAt);
        jdbc.update("""
                        INSERT INTO occupancy.occupancy_event (
                          room_id, sensor_id, event_type, open, observed_at, received_at
                        ) VALUES (?, ?, 'OPENING', ?, ?, ?)
                        """,
                roomId,
                sensorId,
                open,
                Timestamp.from(observed),
                Timestamp.from(observed));
    }
}
