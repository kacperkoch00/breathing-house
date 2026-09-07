package com.breathinghouse.sensorsdatacollector.handler;

public enum SensorType {
    ROOM,
    AIR,
    OPENING,
    PRESENCE,
    STATUS;

    public static SensorType from(String value) {
        return switch (value) {
            case "room" -> ROOM;
            case "air" -> AIR;
            case "opening" -> OPENING;
            case "presence" -> PRESENCE;
            case "status" -> STATUS;
            default -> throw new IllegalArgumentException(
                    "Unknown sensor type: " + value
            );
        };
    }
}
