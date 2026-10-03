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
    void snapshotsExposeSensorId() {
        assign("living-room", "air-1");
        assign("living-room", "air-2");
        insertEnvironment("living-room", "air-1", "AIR", 700.0, "2026-10-03T09:00:00Z");
        insertEnvironment("living-room", "air-2", "AIR", 800.0, "2026-10-03T09:00:00Z");

        assertThat(repository.latestEnvironment(SensorType.AIR))
                .extracting(AlertRepository.EnvironmentSnapshot::sensorId)
                .containsExactlyInAnyOrder("air-1", "air-2");
    }

    @Test
    void unassignedSensorReadingsAreExcluded() {
        assign(null, "air-1");
        insertEnvironment("living-room", "air-1", "AIR", 1600.0, "2026-10-03T09:00:00Z");
        insertEnvironment(null, "air-1", "AIR", 500.0, "2026-10-03T10:00:00Z");
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
    void findAlertsOrdersByTriggeredAtThenIdDescending() {
        long oldest = insertAlert("r1", "room-a", "air-1", "WARNING", "RESOLVED", "2026-10-03T08:00:00Z");
        long tieFirst = insertAlert("r2", "room-a", "air-1", "WARNING", "ACTIVE", "2026-10-03T09:00:00Z");
        long tieSecond = insertAlert("r3", "room-a", "air-1", "WARNING", "ACTIVE", "2026-10-03T09:00:00Z");

        var page = repository.findAlerts(null, null, null, null, null, null, 100, 0);

        assertThat(page.items()).extracting(Alert::id).containsExactly(tieSecond, tieFirst, oldest);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void findAlertsAppliesEveryFilter() {
        insertAlert("r1", "room-a", "air-1", "WARNING", "ACTIVE", "2026-10-03T08:00:00Z");
        long target = insertAlert("r2", "room-b", "air-2", "CRITICAL", "ACTIVE", "2026-10-03T10:00:00Z");
        insertAlert("r3", "room-b", "air-2", "CRITICAL", "RESOLVED", "2026-10-03T10:00:00Z");
        insertAlert("r4", "room-b", "air-3", "CRITICAL", "ACTIVE", "2026-10-03T10:00:00Z");
        insertAlert("r5", "room-b", "air-2", "INFO", "ACTIVE", "2026-10-03T10:00:00Z");
        insertAlert("r6", "room-b", "air-2", "CRITICAL", "ACTIVE", "2026-10-03T12:00:00Z");

        assertThat(repository.findAlerts(
                AlertStatus.ACTIVE, "room-b", "air-2", AlertConfiguration.Severity.CRITICAL,
                Instant.parse("2026-10-03T09:00:00Z"), Instant.parse("2026-10-03T11:00:00Z"), 100, 0).items())
                .extracting(Alert::id).containsExactly(target);

        assertThat(repository.findAlerts(AlertStatus.RESOLVED, null, null, null, null, null, 100, 0).items())
                .extracting(Alert::ruleId).containsExactly("r3");
        assertThat(repository.findAlerts(null, "room-a", null, null, null, null, 100, 0).items())
                .extracting(Alert::ruleId).containsExactly("r1");
        assertThat(repository.findAlerts(null, null, "air-3", null, null, null, 100, 0).items())
                .extracting(Alert::ruleId).containsExactly("r4");
        assertThat(repository.findAlerts(null, null, null, AlertConfiguration.Severity.INFO, null, null, 100, 0).items())
                .extracting(Alert::ruleId).containsExactly("r5");
        assertThat(repository.findAlerts(
                null, null, null, null, Instant.parse("2026-10-03T11:00:00Z"), null, 100, 0).items())
                .extracting(Alert::ruleId).containsExactly("r6");
        assertThat(repository.findAlerts(
                null, null, null, null, null, Instant.parse("2026-10-03T08:00:00Z"), 100, 0).items())
                .extracting(Alert::ruleId).containsExactly("r1");
    }

    @Test
    void findAlertsPaginatesWithHasMore() {
        for (int i = 0; i < 5; i++) {
            insertAlert("r" + i, "room-a", "air-1", "WARNING", "ACTIVE", "2026-10-03T0" + i + ":00:00Z");
        }

        var first = repository.findAlerts(null, null, null, null, null, null, 2, 0);
        var second = repository.findAlerts(null, null, null, null, null, null, 2, 2);
        var last = repository.findAlerts(null, null, null, null, null, null, 2, 4);

        assertThat(first.items()).extracting(Alert::ruleId).containsExactly("r4", "r3");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.limit()).isEqualTo(2);
        assertThat(second.items()).extracting(Alert::ruleId).containsExactly("r2", "r1");
        assertThat(second.hasMore()).isTrue();
        assertThat(second.offset()).isEqualTo(2);
        assertThat(last.items()).extracting(Alert::ruleId).containsExactly("r0");
        assertThat(last.hasMore()).isFalse();
    }

    @Test
    void compositeAlertsExposeNullSensorIdAndResolvedAt() {
        long composite = insertAlert("composite", "room-a", null, "WARNING", "ACTIVE", "2026-10-03T08:00:00Z");

        Alert alert = repository.findAlerts(null, null, null, null, null, null, 100, 0).items().getFirst();

        assertThat(alert.id()).isEqualTo(composite);
        assertThat(alert.sensorId()).isNull();
        assertThat(alert.resolvedAt()).isNull();
        assertThat(alert.status()).isEqualTo(AlertStatus.ACTIVE);
        assertThat(alert.severity()).isEqualTo(AlertConfiguration.Severity.WARNING);
        assertThat(alert.triggeredAt()).isEqualTo(Instant.parse("2026-10-03T08:00:00Z"));
    }

    @Test
    void findAlertByIdIncludesRuleSnapshotAndReturnsEmptyWhenMissing() {
        long id = insertAlert("high-co2", "room-a", "air-1", "WARNING", "RESOLVED", "2026-10-03T08:00:00Z");

        AlertDetail detail = repository.findAlertById(id).orElseThrow();

        assertThat(detail.ruleId()).isEqualTo("high-co2");
        assertThat(detail.sensorId()).isEqualTo("air-1");
        assertThat(detail.resolvedAt()).isEqualTo(Instant.parse("2026-10-03T08:30:00Z"));
        assertThat(detail.ruleSnapshot()).isEqualTo("{\"id\":\"high-co2\",\"threshold\":1500}");
        assertThat(repository.findAlertById(id + 1000)).isEmpty();
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
                  rule_id, room_id, sensor_id, severity, status, message, triggered_at,
                  last_evaluated_at, rule_snapshot
                ) VALUES (?, ?, ?, 'WARNING', 'ACTIVE', 'm', ?, ?, '{}')
                """, ruleId, roomId, sensorId, Timestamp.from(now), Timestamp.from(now));
    }

    private long insertAlert(
            String ruleId,
            String roomId,
            String sensorId,
            String severity,
            String status,
            String triggeredAt) {
        Instant triggered = Instant.parse(triggeredAt);
        Timestamp resolved = "RESOLVED".equals(status) ? Timestamp.from(triggered.plusSeconds(1800)) : null;
        jdbc.update("""
                INSERT INTO home_api.alert (
                  rule_id, room_id, sensor_id, severity, status, message, trigger_value,
                  triggered_at, resolved_at, last_evaluated_at, rule_snapshot
                ) VALUES (?, ?, ?, ?, ?, 'm', '1600', ?, ?, ?, ? FORMAT JSON)
                """,
                ruleId, roomId, sensorId, severity, status, Timestamp.from(triggered), resolved,
                Timestamp.from(triggered.plusSeconds(60)),
                "{\"id\":\"" + ruleId + "\",\"threshold\":1500}");
        return jdbc.queryForObject("SELECT MAX(id) FROM home_api.alert WHERE rule_id = ?", Long.class, ruleId);
    }

    private String statusOf(String ruleId, String roomId, String sensorId) {
        return jdbc.queryForObject("""
                SELECT status FROM home_api.alert
                WHERE rule_id = ? AND room_id = ? AND COALESCE(sensor_id, '') = ?
                """, String.class, ruleId, roomId, sensorId == null ? "" : sensorId);
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
