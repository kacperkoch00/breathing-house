package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.InvalidSensorPayloadException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

final class PayloadRules {

    private PayloadRules() {
    }

    static double requireNumber(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new InvalidSensorPayloadException("Missing required field: " + field);
        }
        if (!(value instanceof Number number)) {
            throw new InvalidSensorPayloadException("Field '" + field + "' must be a number");
        }
        return number.doubleValue();
    }

    static double requireNumber(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new InvalidSensorPayloadException("Missing required field: " + field);
        }
        if (!node.isNumber()) {
            throw new InvalidSensorPayloadException("Field '" + field + "' must be a number");
        }
        return node.asDouble();
    }

    static void requireInRange(double value, double min, double max, String field) {
        if (value < min || value > max) {
            throw new InvalidSensorPayloadException(
                    "Field '" + field + "' must be between " + min + " and " + max + " (was " + value + ")"
            );
        }
    }

    static String requireNonBlank(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new InvalidSensorPayloadException("Missing required field: " + field);
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidSensorPayloadException("Field '" + field + "' must be a non-blank string");
        }
        return text;
    }

    static String requireNonBlank(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new InvalidSensorPayloadException("Missing required field: " + field);
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new InvalidSensorPayloadException("Field '" + field + "' must be a non-blank string");
        }
        return node.asText();
    }
}
