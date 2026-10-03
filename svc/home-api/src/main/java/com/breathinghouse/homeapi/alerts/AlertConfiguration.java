package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.history.EventType;
import com.breathinghouse.homeapi.history.SensorType;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record AlertConfiguration(String evaluationInterval, List<AlertRule> alerts) {

    private static final Pattern SIMPLE_DURATION = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");

    public Validated validate() {
        Duration interval = parseDuration("evaluationInterval", evaluationInterval);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("evaluationInterval must be positive");
        }

        List<AlertRule> configuredRules = alerts == null ? List.of() : List.copyOf(alerts);
        Set<String> ids = new HashSet<>();
        List<ValidatedRule> validatedRules = configuredRules.stream()
                .map(AlertRule::validate)
                .peek(rule -> {
                    if (!ids.add(rule.id())) {
                        throw new IllegalArgumentException("duplicate alert id: " + rule.id());
                    }
                })
                .toList();
        return new Validated(interval, validatedRules);
    }

    static Duration parseDuration(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = SIMPLE_DURATION.matcher(normalized);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("unsupported duration unit");
            };
        }

        try {
            return Duration.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a duration such as 10s, 5m, or PT10S", ex);
        }
    }

    public record AlertRule(
            String id,
            Boolean enabled,
            RuleType type,
            Source source,
            SensorType sensorType,
            EventType eventType,
            Metric metric,
            Operator operator,
            Double threshold,
            BooleanField field,
            Boolean expected,
            @JsonProperty("for") String forDuration,
            String maxDataAge,
            Severity severity,
            List<String> rooms,
            String message) {

        ValidatedRule validate() {
            String normalizedId = requireText("alert id", id);
            RuleType requiredType = require("type", type);
            Source requiredSource = require("source", source);
            Severity requiredSeverity = require("severity", severity);
            String requiredMessage = requireText("message", message);
            Duration holdDuration = parseDuration("for", forDuration);
            if (holdDuration.isNegative()) {
                throw new IllegalArgumentException("for must not be negative for alert " + normalizedId);
            }
            Duration dataAge = maxDataAge == null
                    ? null
                    : parseDuration("maxDataAge", maxDataAge);
            if (dataAge != null && (dataAge.isZero() || dataAge.isNegative())) {
                throw new IllegalArgumentException("maxDataAge must be positive for alert " + normalizedId);
            }

            validateShape(normalizedId, requiredType, requiredSource);
            List<String> configuredRooms = rooms == null || rooms.isEmpty() ? List.of("*") : List.copyOf(rooms);
            if (configuredRooms.stream().anyMatch(room -> room == null || room.isBlank())) {
                throw new IllegalArgumentException("rooms must not contain blank values for alert " + normalizedId);
            }

            return new ValidatedRule(
                    normalizedId,
                    enabled == null || enabled,
                    requiredType,
                    requiredSource,
                    sensorType,
                    eventType,
                    metric,
                    operator,
                    threshold,
                    field,
                    expected,
                    holdDuration,
                    dataAge,
                    requiredSeverity,
                    configuredRooms,
                    requiredMessage,
                    this);
        }

        private void validateShape(String ruleId, RuleType ruleType, Source ruleSource) {
            switch (ruleType) {
                case THRESHOLD -> {
                    if (ruleSource != Source.ENVIRONMENT) {
                        throw new IllegalArgumentException("THRESHOLD requires ENVIRONMENT source: " + ruleId);
                    }
                    require("sensorType", sensorType);
                    require("metric", metric);
                    require("operator", operator);
                    require("threshold", threshold);
                    validateMetric(ruleId);
                }
                case BOOLEAN_STATE -> {
                    if (ruleSource != Source.OCCUPANCY) {
                        throw new IllegalArgumentException("BOOLEAN_STATE requires OCCUPANCY source: " + ruleId);
                    }
                    require("eventType", eventType);
                    require("field", field);
                    require("expected", expected);
                    if ((eventType == EventType.PRESENCE && field != BooleanField.PRESENT)
                            || (eventType == EventType.OPENING && field != BooleanField.OPEN)) {
                        throw new IllegalArgumentException("field does not match eventType for alert " + ruleId);
                    }
                }
                case STALE_DATA -> {
                    if (ruleSource == Source.ENVIRONMENT) {
                        require("sensorType", sensorType);
                    } else {
                        require("eventType", eventType);
                    }
                }
            }
        }

        private void validateMetric(String ruleId) {
            if (sensorType == SensorType.AIR && (metric == Metric.LIGHT || metric == Metric.LIGHT_LEVEL)) {
                throw new IllegalArgumentException("AIR does not provide " + metric + " for alert " + ruleId);
            }
            if (sensorType == SensorType.ROOM && (metric == Metric.HUMIDITY || metric == Metric.CO2)) {
                throw new IllegalArgumentException("ROOM does not provide " + metric + " for alert " + ruleId);
            }
            if (metric == Metric.LIGHT_LEVEL) {
                throw new IllegalArgumentException("LIGHT_LEVEL is not numeric and cannot use THRESHOLD: " + ruleId);
            }
        }

        private static String requireText(String field, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value.trim();
        }

        private static <T> T require(String field, T value) {
            if (value == null) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }

    public record Validated(Duration evaluationInterval, List<ValidatedRule> alerts) {
    }

    public record ValidatedRule(
            String id,
            boolean enabled,
            RuleType type,
            Source source,
            SensorType sensorType,
            EventType eventType,
            Metric metric,
            Operator operator,
            Double threshold,
            BooleanField field,
            Boolean expected,
            Duration holdDuration,
            Duration maxDataAge,
            Severity severity,
            List<String> rooms,
            String message,
            AlertRule sourceRule) {

        public boolean appliesTo(String roomId) {
            return rooms.contains("*") || rooms.contains(roomId);
        }
    }

    public enum RuleType {
        THRESHOLD,
        BOOLEAN_STATE,
        STALE_DATA
    }

    public enum Source {
        ENVIRONMENT,
        OCCUPANCY
    }

    public enum Metric {
        TEMPERATURE,
        HUMIDITY,
        CO2,
        LIGHT,
        LIGHT_LEVEL
    }

    public enum Operator {
        GREATER_THAN {
            @Override
            public boolean test(double value, double threshold) {
                return value > threshold;
            }
        },
        GREATER_THAN_OR_EQUAL {
            @Override
            public boolean test(double value, double threshold) {
                return value >= threshold;
            }
        },
        LESS_THAN {
            @Override
            public boolean test(double value, double threshold) {
                return value < threshold;
            }
        },
        LESS_THAN_OR_EQUAL {
            @Override
            public boolean test(double value, double threshold) {
                return value <= threshold;
            }
        },
        EQUALS {
            @Override
            public boolean test(double value, double threshold) {
                return Double.compare(value, threshold) == 0;
            }
        };

        public abstract boolean test(double value, double threshold);
    }

    public enum BooleanField {
        PRESENT,
        OPEN
    }

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }
}
