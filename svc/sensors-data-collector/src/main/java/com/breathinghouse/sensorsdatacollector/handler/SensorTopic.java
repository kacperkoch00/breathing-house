package com.breathinghouse.sensorsdatacollector.handler;

public record SensorTopic(String roomId, String sensorType) {

    public static SensorTopic parse(String topic) {
        String[] parts = topic.split("/", -1);

        if (parts.length != 3 || !"home".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid sensor topic: " + topic);
        }

        return new SensorTopic(parts[1], parts[2]);
    }
}
