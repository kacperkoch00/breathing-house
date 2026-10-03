package com.breathinghouse.homeapi.rooms;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateRoomRequest(JsonNode name, JsonNode description) {

    public String requireName() {
        return RoomFields.requireName(name);
    }

    public String descriptionOrNull() {
        return RoomFields.optionalDescription(description);
    }
}
