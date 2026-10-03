package com.breathinghouse.homeapi.rooms;

import java.util.List;

public record RoomSummary(String roomId, String name, String description, List<String> sensorIds) {
}
