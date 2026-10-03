package com.breathinghouse.homeapi.rooms;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record UpdateRoomRequest(@NotNull JsonNode displayName) {

    public String requireDisplayNameString() {
        if (displayName == null || displayName.isNull()) {
            throw new IllegalArgumentException("displayName must be provided");
        }
        if (!displayName.isTextual()) {
            throw new IllegalArgumentException("displayName must be a string");
        }
        return displayName.asText();
    }
}
