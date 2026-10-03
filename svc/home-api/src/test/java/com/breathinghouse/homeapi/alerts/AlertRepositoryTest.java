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
    }

    @Test
    void returnsOnlyLatestEnvironmentReadingPerDevice() {
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
    void returnsOnlyLatestOccupancyEventPerDevice() {
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
    void readinessIncludesAlertTables() {
        assertThat(repository.isReady()).isTrue();
    }

    private void insertEnvironment(
            String roomId,
            String deviceId,
            String sensorType,
            double co2,
            String observedAt) {
        Instant observed = Instant.parse(observedAt);
        jdbc.update("""
                        INSERT INTO environment.environment_reading (
                          room_id, device_id, sensor_type, co2, observed_at, received_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                roomId,
                deviceId,
                sensorType,
                co2,
                Timestamp.from(observed),
                Timestamp.from(observed));
    }

    private void insertOccupancy(
            String roomId,
            String deviceId,
            boolean open,
            String observedAt) {
        Instant observed = Instant.parse(observedAt);
        jdbc.update("""
                        INSERT INTO occupancy.occupancy_event (
                          room_id, device_id, event_type, open, observed_at, received_at
                        ) VALUES (?, ?, 'OPENING', ?, ?, ?)
                        """,
                roomId,
                deviceId,
                open,
                Timestamp.from(observed),
                Timestamp.from(observed));
    }
}
