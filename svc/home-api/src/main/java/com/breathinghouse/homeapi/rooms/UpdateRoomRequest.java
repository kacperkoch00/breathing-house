package com.breathinghouse.homeapi.rooms;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdateRoomRequest(JsonNode name, JsonNode description) {

    public RoomPatch toPatch() {
        boolean hasName = name != null;
        boolean hasDescription = description != null;
        if (!hasName && !hasDescription) {
            throw new IllegalArgumentException("at least one of name or description must be provided");
        }
        return new RoomPatch(
                hasName ? RoomFields.requireName(name) : null,
                hasDescription,
                hasDescription ? RoomFields.optionalDescription(description) : null);
    }
}
