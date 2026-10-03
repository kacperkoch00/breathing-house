package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertConfiguration.Validated;
import com.breathinghouse.homeapi.config.JdbcConfig;
import com.breathinghouse.homeapi.rooms.RoomRepository;
import com.breathinghouse.homeapi.sensors.SensorRepository;
import com.breathinghouse.homeapi.sensors.SensorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@JdbcTest
@Import({AlertRepository.class, RoomRepository.class, SensorRepository.class, JdbcConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class SensorReassignmentAlertsIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AlertEvaluationService evaluationService;
    private SensorService sensorService;
    private Validated configuration;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM home_api.alert_state");
        jdbc.update("DELETE FROM home_api.alert");
        jdbc.update("DELETE FROM home_api.sensor");
        jdbc.update("DELETE FROM home_api.room");
        jdbc.update("DELETE FROM environment.environment_reading");
        jdbc.update("DELETE FROM occupancy.occupancy_event");

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        configuration = objectMapper.readValue("""
                {
                  "evaluationInterval": "10s",
                  "alerts": [
                    {
                      "id": "high-co2",
                      "type": "THRESHOLD",
                      "source": "ENVIRONMENT",
                      "sensorType": "AIR",
                      "metric": "CO2",
                      "operator": "GREATER_THAN",
                      "threshold": 1500,
                      "for": "0s",
                      "maxDataAge": "2m",
                      "severity": "WARNING",
                      "message": "CO2 {{value}} from {{sensorId}} in {{roomId}}"
                    },
                    {
                      "id": "air-data-stale",
                      "type": "STALE_DATA",
                      "source": "ENVIRONMENT",
                      "sensorType": "AIR",
                      "for": "5m",
                      "severity": "INFO",
                      "message": "No recent air reading from {{sensorId}} in {{roomId}}"
                    },
                    {
                      "id": "poor-air-and-hot",
                      "type": "COMPOSITE",
                      "combinator": "ALL",
                      "for": "0s",
                      "severity": "WARNING",
                      "message": "Poor conditions in {{roomId}}: {{values}}",
                      "conditions": [
                        {
                          "id": "co2",
                          "type": "THRESHOLD",
                          "source": "ENVIRONMENT",
                          "sensorType": "AIR",
                          "metric": "CO2",
                          "operator": "GREATER_THAN",
                          "threshold": 1200,
                          "maxDataAge": "2m"
                        },
                        {
                          "id": "temperature",
                          "type": "THRESHOLD",
                          "source": "ENVIRONMENT",
                          "sensorType": "ROOM",
                          "metric": "TEMPERATURE",
                          "operator": "GREATER_THAN",
                          "threshold": 28,
                          "maxDataAge": "2m"
                        }
                      ]
                    }
                  ]
                }
                """, AlertConfiguration.class).validate();

        AlertConfigurationLoader loader = mock(AlertConfigurationLoader.class);
        when(loader.current()).thenReturn(configuration);
        evaluationService = new AlertEvaluationService(alertRepository, objectMapper, clock);
        sensorService = new SensorService(
                sensorRepository,
                roomRepository,
                new SensorReassignmentAlertHandler(alertRepository, evaluationService, loader, clock),
                clock);

        insertRoom("room-a");
        insertRoom("room-b");
    }

    @Test
    void movingASensorResolvesItsAlertInTheOldRoomWithoutMovingItToTheNewRoom() {
        insertSensor("air-1", "room-a");
        insertAir("room-a", "air-1", 1600.0, NOW.minusSeconds(30));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("high-co2")).containsExactly("room-a|air-1");
        long alertId = jdbc.queryForObject(
                "SELECT id FROM home_api.alert WHERE rule_id = 'high-co2'", Long.class);

        sensorService.assign("room-b", "air-1");

        assertThat(activeAlerts("high-co2")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM home_api.alert WHERE id = ?", String.class, alertId))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT room_id FROM home_api.alert WHERE id = ?", String.class, alertId))
                .isEqualTo("room-a");
        assertThat(alertState("high-co2", "room-a", "air-1")).isFalse();

        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("high-co2")).isEmpty();

        insertAir("room-b", "air-1", 1700.0, NOW.minusSeconds(10));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("high-co2")).containsExactly("room-b|air-1");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM home_api.alert WHERE id = ?", String.class, alertId))
                .isEqualTo("RESOLVED");
    }

    @Test
    void unassigningResolvesAlertsAndUnassignedReadingsNeverAlert() {
        insertSensor("air-1", "room-a");
        insertAir("room-a", "air-1", 1600.0, NOW.minusSeconds(30));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("high-co2")).containsExactly("room-a|air-1");

        sensorService.unassign("room-a", "air-1");
        insertAir(null, "air-1", 1900.0, NOW.minusSeconds(5));
        evaluationService.evaluate(configuration);

        assertThat(activeAlerts("high-co2")).isEmpty();
        assertThat(activeAlerts("air-data-stale")).isEmpty();
    }

    @Test
    void staleDataAlertForOldRoomIsResolvedAndNotRecreatedAfterReassignment() {
        insertSensor("air-1", "room-a");
        insertAir("room-a", "air-1", 500.0, NOW.minusSeconds(20 * 60));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("air-data-stale")).containsExactly("room-a|air-1");

        sensorService.assign("room-b", "air-1");
        evaluationService.evaluate(configuration);

        assertThat(activeAlerts("air-data-stale")).isEmpty();

        insertAir("room-b", "air-1", 500.0, NOW.minusSeconds(10 * 60));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("air-data-stale")).containsExactly("room-b|air-1");
    }

    @Test
    void compositeAlertUsesCurrentAssignmentsAndResolvesImmediatelyWhenASensorLeaves() {
        insertSensor("air-1", "room-a");
        insertSensor("room-1", "room-a");
        insertAir("room-a", "air-1", 1450.0, NOW.minusSeconds(30));
        insertRoomTemperature("room-a", "room-1", 29.5, NOW.minusSeconds(30));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("poor-air-and-hot")).containsExactly("room-a|");

        sensorService.assign("room-b", "air-1");

        assertThat(activeAlerts("poor-air-and-hot")).isEmpty();
        assertThat(alertState("poor-air-and-hot", "room-a", "")).isFalse();

        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("poor-air-and-hot")).isEmpty();

        sensorService.assign("room-a", "air-1");
        insertAir("room-a", "air-1", 1450.0, NOW.minusSeconds(5));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("poor-air-and-hot")).containsExactly("room-a|");
    }

    @Test
    void compositeAlertResolvesWhenTheLastContributingSensorIsUnassigned() {
        insertSensor("air-1", "room-a");
        insertSensor("room-1", "room-a");
        insertAir("room-a", "air-1", 1450.0, NOW.minusSeconds(30));
        insertRoomTemperature("room-a", "room-1", 29.5, NOW.minusSeconds(30));
        evaluationService.evaluate(configuration);
        assertThat(activeAlerts("poor-air-and-hot")).containsExactly("room-a|");

        sensorService.unassign("room-a", "room-1");

        assertThat(activeAlerts("poor-air-and-hot")).isEmpty();
    }

    private List<String> activeAlerts(String ruleId) {
        return jdbc.queryForList("""
                SELECT room_id || '|' || COALESCE(sensor_id, '')
                FROM home_api.alert
                WHERE rule_id = ? AND status = 'ACTIVE'
                ORDER BY room_id
                """, String.class, ruleId);
    }

    private boolean alertState(String ruleId, String roomId, String sensorKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT condition_active FROM home_api.alert_state
                WHERE rule_id = ? AND room_id = ? AND sensor_key = ?
                """, Boolean.class, ruleId, roomId, sensorKey));
    }

    private void insertRoom(String roomId) {
        jdbc.update("INSERT INTO home_api.room (room_id, name) VALUES (?, ?)", roomId, roomId);
    }

    private void insertSensor(String sensorId, String roomId) {
        jdbc.update("""
                INSERT INTO home_api.sensor (sensor_id, display_name, room_id)
                VALUES (?, ?, ?)
                """, sensorId, sensorId, roomId);
    }

    private void insertAir(String roomId, String sensorId, double co2, Instant observedAt) {
        jdbc.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_id, sensor_type, co2, observed_at, received_at
                ) VALUES (?, ?, 'AIR', ?, ?, ?)
                """, roomId, sensorId, co2, Timestamp.from(observedAt), Timestamp.from(observedAt));
    }

    private void insertRoomTemperature(String roomId, String sensorId, double temperature, Instant observedAt) {
        jdbc.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_id, sensor_type, temperature, observed_at, received_at
                ) VALUES (?, ?, 'ROOM', ?, ?, ?)
                """, roomId, sensorId, temperature, Timestamp.from(observedAt), Timestamp.from(observedAt));
    }
}
