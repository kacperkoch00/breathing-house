package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertConfiguration.Validated;
import com.breathinghouse.homeapi.alerts.AlertConfiguration.ValidatedRule;
import com.breathinghouse.homeapi.alerts.AlertRepository.AlertState;
import com.breathinghouse.homeapi.alerts.AlertRepository.EnvironmentSnapshot;
import com.breathinghouse.homeapi.alerts.AlertRepository.OccupancySnapshot;
import com.breathinghouse.homeapi.history.EventType;
import com.breathinghouse.homeapi.history.SensorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertEvaluationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AlertRepository repository;
    private AlertEvaluationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlertRepository.class);
        service = new AlertEvaluationService(
                repository,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void activatesThresholdAlertAfterConfiguredDuration() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "high-co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1500,
                  "for": "5m",
                  "maxDataAge": "10m",
                  "severity": "WARNING",
                  "message": "CO2 in {{roomId}} is {{value}}"
                }
                """);
        ValidatedRule rule = configuration.alerts().getFirst();
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "air-1",
                        SensorType.AIR,
                        22.0,
                        45.0,
                        1600.0,
                        null,
                        NOW.minusSeconds(6 * 60))));
        when(repository.findState("high-co2", "living-room", "air-1"))
                .thenReturn(Optional.empty());

        service.evaluate(configuration);

        verify(repository).createAlert(
                eq(rule),
                eq("living-room"),
                eq("air-1"),
                contains("1600"),
                eq("1600"),
                eq(NOW),
                anyString());
    }

    @Test
    void keepsThresholdPendingBeforeConfiguredDuration() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "high-co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1500,
                  "for": "5m",
                  "maxDataAge": "10m",
                  "severity": "WARNING",
                  "message": "high"
                }
                """);
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "air-1",
                        SensorType.AIR,
                        null,
                        null,
                        1600.0,
                        null,
                        NOW.minusSeconds(60))));
        when(repository.findState(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        service.evaluate(configuration);

        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
        ArgumentCaptor<AlertState> state = ArgumentCaptor.forClass(AlertState.class);
        verify(repository).saveState(state.capture());
        assertThat(state.getValue().conditionActive()).isTrue();
        assertThat(state.getValue().conditionStartedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void resetsPendingDurationWhenRuleConfigurationChanges() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "high-co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1500,
                  "for": "5m",
                  "maxDataAge": "10m",
                  "severity": "WARNING",
                  "message": "high"
                }
                """);
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "air-1",
                        SensorType.AIR,
                        null,
                        null,
                        1600.0,
                        null,
                        NOW.minusSeconds(60))));
        when(repository.findState("high-co2", "living-room", "air-1")).thenReturn(Optional.of(
                new AlertState(
                        "high-co2",
                        "living-room",
                        "air-1",
                        true,
                        NOW.minusSeconds(10 * 60),
                        "1600",
                        "old-rule",
                        NOW.minusSeconds(10))));

        service.evaluate(configuration);

        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
        ArgumentCaptor<AlertState> state = ArgumentCaptor.forClass(AlertState.class);
        verify(repository).saveState(state.capture());
        assertThat(state.getValue().conditionStartedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void resolvesAlertWhenConditionClears() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "high-co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1500,
                  "for": "5m",
                  "maxDataAge": "10m",
                  "severity": "WARNING",
                  "message": "high"
                }
                """);
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        null,
                        SensorType.AIR,
                        null,
                        null,
                        700.0,
                        null,
                        NOW)));

        service.evaluate(configuration);

        verify(repository).resolveActiveAlert("high-co2", "living-room", null, NOW);
    }

    @Test
    void activatesOpeningAlertFromObservedEventTime() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "opening-left-open",
                  "type": "BOOLEAN_STATE",
                  "source": "OCCUPANCY",
                  "eventType": "OPENING",
                  "field": "OPEN",
                  "expected": true,
                  "for": "15m",
                  "severity": "WARNING",
                  "message": "open in {{roomId}} for {{duration}}"
                }
                """);
        when(repository.latestOccupancy(EventType.OPENING)).thenReturn(List.of(
                new OccupancySnapshot(
                        "kitchen",
                        "window-1",
                        EventType.OPENING,
                        null,
                        true,
                        NOW.minusSeconds(20 * 60))));

        service.evaluate(configuration);

        verify(repository).createAlert(
                any(),
                eq("kitchen"),
                eq("window-1"),
                contains("PT15M"),
                eq("true"),
                eq(NOW),
                anyString());
    }

    @Test
    void compositeAllActivatesOnlyWhenEveryConditionIsTrue() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "0s"));
        ValidatedRule rule = configuration.alerts().getFirst();
        stubCompositeSnapshots(1450.0, 29.5, NOW.minusSeconds(60), NOW.minusSeconds(90));
        when(repository.findState("poor-air-and-hot", "living-room", null)).thenReturn(Optional.empty());

        service.evaluate(configuration);

        verify(repository).createAlert(
                eq(rule),
                eq("living-room"),
                isNull(),
                contains("living-room"),
                eq("{\"co2\":\"1450\",\"temperature\":\"29.5\"}"),
                eq(NOW),
                anyString());
    }

    @Test
    void compositeAllDoesNotActivateWhenOneConditionIsFalse() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "0s"));
        stubCompositeSnapshots(1450.0, 20.0, NOW.minusSeconds(60), NOW.minusSeconds(90));

        service.evaluate(configuration);

        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
        verify(repository).resolveActiveAlert("poor-air-and-hot", "living-room", null, NOW);
    }

    @Test
    void compositeAnyActivatesWhenOneConditionIsTrue() throws Exception {
        Validated configuration = configuration(compositeRule("ANY", "0s"));
        ValidatedRule rule = configuration.alerts().getFirst();
        stubCompositeSnapshots(1450.0, 20.0, NOW.minusSeconds(60), NOW.minusSeconds(90));
        when(repository.findState("poor-air-and-hot", "living-room", null)).thenReturn(Optional.empty());

        service.evaluate(configuration);

        verify(repository).createAlert(
                eq(rule),
                eq("living-room"),
                isNull(),
                anyString(),
                eq("{\"co2\":\"1450\"}"),
                eq(NOW),
                anyString());
    }

    @Test
    void compositeTreatsMissingConditionDataAsFalse() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "0s"));
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "air-1",
                        SensorType.AIR,
                        null,
                        null,
                        1450.0,
                        null,
                        NOW.minusSeconds(60))));
        when(repository.latestEnvironment(SensorType.ROOM)).thenReturn(List.of());

        service.evaluate(configuration);

        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
        verify(repository).resolveActiveAlert("poor-air-and-hot", "living-room", null, NOW);
    }

    @Test
    void compositeTreatsStaleConditionDataAsFalse() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "0s"));
        stubCompositeSnapshots(1450.0, 29.5, NOW.minusSeconds(60), NOW.minusSeconds(10 * 60));

        service.evaluate(configuration);

        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
        verify(repository).resolveActiveAlert("poor-air-and-hot", "living-room", null, NOW);
    }

    @Test
    void compositeUsesNewestDeviceObservationPerRoom() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "0s"));
        ValidatedRule rule = configuration.alerts().getFirst();
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "air-old",
                        SensorType.AIR,
                        null,
                        null,
                        900.0,
                        null,
                        NOW.minusSeconds(120)),
                new EnvironmentSnapshot(
                        "living-room",
                        "air-new",
                        SensorType.AIR,
                        null,
                        null,
                        1450.0,
                        null,
                        NOW.minusSeconds(30))));
        when(repository.latestEnvironment(SensorType.ROOM)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "room-1",
                        SensorType.ROOM,
                        29.5,
                        null,
                        null,
                        null,
                        NOW.minusSeconds(45))));
        when(repository.findState("poor-air-and-hot", "living-room", null)).thenReturn(Optional.empty());

        service.evaluate(configuration);

        verify(repository).createAlert(
                eq(rule),
                eq("living-room"),
                isNull(),
                anyString(),
                eq("{\"co2\":\"1450\",\"temperature\":\"29.5\"}"),
                eq(NOW),
                anyString());
    }

    @Test
    void compositeRespectsParentForDuration() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "5m"));
        stubCompositeSnapshots(1450.0, 29.5, NOW.minusSeconds(60), NOW.minusSeconds(90));
        when(repository.findState("poor-air-and-hot", "living-room", null)).thenReturn(Optional.empty());

        service.evaluate(configuration);

        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
        ArgumentCaptor<AlertState> state = ArgumentCaptor.forClass(AlertState.class);
        verify(repository).saveState(state.capture());
        assertThat(state.getValue().conditionActive()).isTrue();
        assertThat(state.getValue().sensorId()).isNull();
        // ALL uses latest observed_at among true conditions
        assertThat(state.getValue().conditionStartedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void compositeResolvesWhenOneAllConditionClears() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "0s"));
        stubCompositeSnapshots(1450.0, 20.0, NOW.minusSeconds(60), NOW.minusSeconds(90));

        service.evaluate(configuration);

        verify(repository).resolveActiveAlert("poor-air-and-hot", "living-room", null, NOW);
        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void compositeRendersValuesPlaceholderAndNullDeviceId() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "poor-air-and-hot",
                  "type": "COMPOSITE",
                  "combinator": "ALL",
                  "for": "0s",
                  "severity": "WARNING",
                  "rooms": ["living-room"],
                  "message": "Poor conditions in {{roomId}} for {{duration}}: {{values}}",
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
                """);
        stubCompositeSnapshots(1450.0, 29.5, NOW.minusSeconds(60), NOW.minusSeconds(90));
        when(repository.findState("poor-air-and-hot", "living-room", null)).thenReturn(Optional.empty());

        service.evaluate(configuration);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(repository).createAlert(
                any(),
                eq("living-room"),
                isNull(),
                message.capture(),
                eq("{\"co2\":\"1450\",\"temperature\":\"29.5\"}"),
                eq(NOW),
                anyString());
        assertThat(message.getValue()).isEqualTo(
                "Poor conditions in living-room for PT0S: {\"co2\":\"1450\",\"temperature\":\"29.5\"}");
    }

    @Test
    void compositeResetsPendingDurationWhenRuleConfigurationChanges() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "5m"));
        stubCompositeSnapshots(1450.0, 29.5, NOW.minusSeconds(60), NOW.minusSeconds(90));
        when(repository.findState("poor-air-and-hot", "living-room", null)).thenReturn(Optional.of(
                new AlertState(
                        "poor-air-and-hot",
                        "living-room",
                        null,
                        true,
                        NOW.minusSeconds(10 * 60),
                        "{\"co2\":\"1450\",\"temperature\":\"29.5\"}",
                        "old-composite",
                        NOW.minusSeconds(10))));

        service.evaluate(configuration);

        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
        ArgumentCaptor<AlertState> state = ArgumentCaptor.forClass(AlertState.class);
        verify(repository).saveState(state.capture());
        assertThat(state.getValue().conditionStartedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void rendersSensorIdPlaceholderAndKeepsDeviceIdAlias() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "high-co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1500,
                  "for": "0s",
                  "maxDataAge": "10m",
                  "severity": "WARNING",
                  "message": "{{sensorId}}/{{deviceId}} in {{roomId}}"
                }
                """);
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room", "air-1", SensorType.AIR, null, null, 1600.0, null,
                        NOW.minusSeconds(30))));
        when(repository.findState("high-co2", "living-room", "air-1")).thenReturn(Optional.empty());

        service.evaluate(configuration);

        verify(repository).createAlert(
                any(),
                eq("living-room"),
                eq("air-1"),
                eq("air-1/air-1 in living-room"),
                eq("1600"),
                eq(NOW),
                anyString());
    }

    @Test
    void reevaluateCompositeRoomsResolvesAlertWhenSensorDataLeftTheRoom() throws Exception {
        Validated configuration = configuration(compositeRule("ALL", "0s"));
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of());
        when(repository.latestEnvironment(SensorType.ROOM)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room", "room-1", SensorType.ROOM, 29.5, null, null, null,
                        NOW.minusSeconds(30))));

        service.reevaluateCompositeRooms(configuration, List.of("living-room"));

        verify(repository).resolveActiveAlert("poor-air-and-hot", "living-room", null, NOW);
        verify(repository, never()).createAlert(any(), anyString(), any(), anyString(), any(), any(), anyString());
    }

    @Test
    void reevaluateCompositeRoomsSkipsRoomsOutsideRuleFilterAndNonCompositeRules() throws Exception {
        Validated configuration = configuration("""
                {
                  "id": "high-co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1500,
                  "for": "0s",
                  "severity": "WARNING",
                  "message": "m"
                }
                """);

        service.reevaluateCompositeRooms(configuration, List.of("living-room"));

        verify(repository, never()).latestEnvironment(any());

        Validated composite = configuration(compositeRule("ALL", "0s"));
        service.reevaluateCompositeRooms(composite, List.of("kitchen"));

        verify(repository, never()).saveState(any());
    }

    private void stubCompositeSnapshots(
            double co2,
            double temperature,
            Instant airObservedAt,
            Instant roomObservedAt) {
        when(repository.latestEnvironment(SensorType.AIR)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "air-1",
                        SensorType.AIR,
                        null,
                        null,
                        co2,
                        null,
                        airObservedAt)));
        when(repository.latestEnvironment(SensorType.ROOM)).thenReturn(List.of(
                new EnvironmentSnapshot(
                        "living-room",
                        "room-1",
                        SensorType.ROOM,
                        temperature,
                        null,
                        null,
                        null,
                        roomObservedAt)));
    }

    private static String compositeRule(String combinator, String forDuration) {
        return """
                {
                  "id": "poor-air-and-hot",
                  "type": "COMPOSITE",
                  "combinator": "%s",
                  "for": "%s",
                  "severity": "WARNING",
                  "rooms": ["living-room"],
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
                """.formatted(combinator, forDuration);
    }

    private Validated configuration(String rule) throws Exception {
        return objectMapper.readValue("""
                {
                  "evaluationInterval": "10s",
                  "alerts": [%s]
                }
                """.formatted(rule), AlertConfiguration.class).validate();
    }
}
