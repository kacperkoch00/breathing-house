package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertConfiguration.BooleanField;
import com.breathinghouse.homeapi.alerts.AlertConfiguration.Metric;
import com.breathinghouse.homeapi.alerts.AlertConfiguration.Source;
import com.breathinghouse.homeapi.alerts.AlertConfiguration.Validated;
import com.breathinghouse.homeapi.alerts.AlertConfiguration.ValidatedRule;
import com.breathinghouse.homeapi.alerts.AlertRepository.AlertState;
import com.breathinghouse.homeapi.alerts.AlertRepository.EnvironmentSnapshot;
import com.breathinghouse.homeapi.alerts.AlertRepository.OccupancySnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    private final AlertRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AlertEvaluationService(AlertRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, Clock.systemUTC());
    }

    AlertEvaluationService(AlertRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void evaluate(Validated configuration) {
        Instant now = clock.instant();
        Set<String> enabledRuleIds = configuration.alerts().stream()
                .filter(ValidatedRule::enabled)
                .map(ValidatedRule::id)
                .collect(Collectors.toUnmodifiableSet());
        repository.resolveAlertsForInactiveRules(enabledRuleIds, now);

        for (ValidatedRule rule : configuration.alerts()) {
            if (!rule.enabled()) {
                continue;
            }
            try {
                repository.resolveAlertsOutsideRooms(rule.id(), rule.rooms(), now);
                evaluateRule(rule, now);
            } catch (RuntimeException ex) {
                log.error("alert rule evaluation failed", ex);
            }
        }
    }

    private void evaluateRule(ValidatedRule rule, Instant now) {
        switch (rule.type()) {
            case THRESHOLD -> evaluateThreshold(rule, now);
            case BOOLEAN_STATE -> evaluateBooleanState(rule, now);
            case STALE_DATA -> evaluateStaleData(rule, now);
        }
    }

    private void evaluateThreshold(ValidatedRule rule, Instant now) {
        for (EnvironmentSnapshot snapshot : repository.latestEnvironment(rule.sensorType())) {
            if (!rule.appliesTo(snapshot.roomId())) {
                continue;
            }

            Double value = metricValue(snapshot, rule.metric());
            boolean fresh = rule.maxDataAge() == null
                    || !snapshot.observedAt().isBefore(now.minus(rule.maxDataAge()));
            boolean condition = fresh
                    && value != null
                    && rule.operator().test(value, rule.threshold());

            evaluateInstance(
                    rule,
                    snapshot.roomId(),
                    snapshot.deviceId(),
                    condition,
                    value == null ? null : formatNumber(value),
                    snapshot.observedAt(),
                    rule.holdDuration(),
                    now);
        }
    }

    private void evaluateBooleanState(ValidatedRule rule, Instant now) {
        for (OccupancySnapshot snapshot : repository.latestOccupancy(rule.eventType())) {
            if (!rule.appliesTo(snapshot.roomId())) {
                continue;
            }

            Boolean value = rule.field() == BooleanField.PRESENT ? snapshot.present() : snapshot.open();
            boolean fresh = rule.maxDataAge() == null
                    || !snapshot.observedAt().isBefore(now.minus(rule.maxDataAge()));
            boolean condition = fresh && value != null && Objects.equals(value, rule.expected());

            evaluateInstance(
                    rule,
                    snapshot.roomId(),
                    snapshot.deviceId(),
                    condition,
                    value == null ? null : value.toString(),
                    snapshot.observedAt(),
                    rule.holdDuration(),
                    now);
        }
    }

    private void evaluateStaleData(ValidatedRule rule, Instant now) {
        if (rule.source() == Source.ENVIRONMENT) {
            List<EnvironmentSnapshot> snapshots = repository.latestEnvironment(rule.sensorType());
            for (EnvironmentSnapshot snapshot : snapshots) {
                if (rule.appliesTo(snapshot.roomId())) {
                    evaluateStaleInstance(
                            rule,
                            snapshot.roomId(),
                            snapshot.deviceId(),
                            snapshot.observedAt(),
                            now);
                }
            }
            return;
        }

        List<OccupancySnapshot> snapshots = repository.latestOccupancy(rule.eventType());
        for (OccupancySnapshot snapshot : snapshots) {
            if (rule.appliesTo(snapshot.roomId())) {
                evaluateStaleInstance(
                        rule,
                        snapshot.roomId(),
                        snapshot.deviceId(),
                        snapshot.observedAt(),
                        now);
            }
        }
    }

    private void evaluateStaleInstance(
            ValidatedRule rule,
            String roomId,
            String deviceId,
            Instant observedAt,
            Instant now) {
        Duration age = Duration.between(observedAt, now);
        boolean stale = !age.isNegative() && age.compareTo(rule.holdDuration()) >= 0;
        evaluateInstance(
                rule,
                roomId,
                deviceId,
                stale,
                Long.toString(Math.max(0, age.toSeconds())),
                observedAt,
                Duration.ZERO,
                now);
    }

    private void evaluateInstance(
            ValidatedRule rule,
            String roomId,
            String deviceId,
            boolean condition,
            String value,
            Instant suggestedConditionStart,
            Duration activationDelay,
            Instant now) {
        String ruleFingerprint = serializeRule(rule);
        AlertState previous = repository.findState(rule.id(), roomId, deviceId).orElse(null);
        if (previous != null && !ruleFingerprint.equals(previous.ruleFingerprint())) {
            previous = null;
        }

        if (!condition) {
            repository.saveState(new AlertState(
                    rule.id(), roomId, deviceId, false, null, value, ruleFingerprint, now));
            repository.resolveActiveAlert(rule.id(), roomId, deviceId, now);
            return;
        }

        Instant conditionStartedAt = previous != null && previous.conditionActive()
                ? previous.conditionStartedAt()
                : noLaterThanNow(suggestedConditionStart, now);
        if (conditionStartedAt == null) {
            conditionStartedAt = now;
        }

        repository.saveState(new AlertState(
                rule.id(), roomId, deviceId, true, conditionStartedAt, value, ruleFingerprint, now));

        if (now.isBefore(conditionStartedAt.plus(activationDelay))) {
            return;
        }

        if (repository.hasActiveAlert(rule.id(), roomId, deviceId)) {
            repository.touchActiveAlert(rule.id(), roomId, deviceId, now);
            return;
        }

        repository.createAlert(
                rule,
                roomId,
                deviceId,
                renderMessage(rule, roomId, deviceId, value),
                value,
                now,
                ruleFingerprint);
        log.info(
                "activated alert rule={} room={} device={} severity={}",
                rule.id(),
                roomId,
                deviceId,
                rule.severity());
    }

    private String renderMessage(ValidatedRule rule, String roomId, String deviceId, String value) {
        return rule.message()
                .replace("{{roomId}}", roomId)
                .replace("{{deviceId}}", deviceId == null ? "" : deviceId)
                .replace("{{value}}", value == null ? "" : value)
                .replace("{{threshold}}", rule.threshold() == null ? "" : formatNumber(rule.threshold()))
                .replace("{{duration}}", rule.holdDuration().toString());
    }

    private String serializeRule(ValidatedRule rule) {
        try {
            return objectMapper.writeValueAsString(rule.sourceRule());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("cannot serialize alert rule " + rule.id(), ex);
        }
    }

    private static Double metricValue(EnvironmentSnapshot snapshot, Metric metric) {
        return switch (metric) {
            case TEMPERATURE -> snapshot.temperature();
            case HUMIDITY -> snapshot.humidity();
            case CO2 -> snapshot.co2();
            case LIGHT -> snapshot.light();
            case LIGHT_LEVEL -> null;
        };
    }

    private static Instant noLaterThanNow(Instant value, Instant now) {
        if (value == null || value.isAfter(now)) {
            return now;
        }
        return value;
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
