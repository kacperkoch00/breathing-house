package com.breathinghouse.homeapi.sensors;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdateSensorRequest(JsonNode displayName) {

    static final int MAX_DISPLAY_NAME_LENGTH = 100;

    public String requireDisplayName() {
        if (displayName == null || displayName.isNull()) {
            throw new IllegalArgumentException("displayName must be provided");
        }
        if (!displayName.isTextual()) {
            throw new IllegalArgumentException("displayName must be a string");
        }
        String trimmed = displayName.asText().trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return trimmed;
    }
}
