package com.breathinghouse.homeapi.sensors;

import com.fasterxml.jackson.databind.JsonNode;

public record PairSensorRequest(JsonNode sensorId, JsonNode displayName) {

    static final int MAX_SENSOR_ID_LENGTH = 200;

    public String requireSensorId() {
        if (sensorId == null || sensorId.isNull()) {
            throw new IllegalArgumentException("sensorId must be provided");
        }
        if (!sensorId.isTextual()) {
            throw new IllegalArgumentException("sensorId must be a string");
        }
        String trimmed = sensorId.asText().trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sensorId must not be blank");
        }
        if (trimmed.length() > MAX_SENSOR_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "sensorId must be at most " + MAX_SENSOR_ID_LENGTH + " characters");
        }
        return trimmed;
    }

    public String displayNameOrNull() {
        if (displayName == null || displayName.isNull()) {
            return null;
        }
        if (!displayName.isTextual()) {
            throw new IllegalArgumentException("displayName must be a string or null");
        }
        String trimmed = displayName.asText().trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (trimmed.length() > UpdateSensorRequest.MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName must be at most " + UpdateSensorRequest.MAX_DISPLAY_NAME_LENGTH
                            + " characters");
        }
        return trimmed;
    }
}
