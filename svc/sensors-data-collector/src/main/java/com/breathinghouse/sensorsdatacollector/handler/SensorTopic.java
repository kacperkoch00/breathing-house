package com.breathinghouse.sensorsdatacollector.handler;

/**
 * Parsed MQTT topic. {@code roomId} is only set for {@code home/gateway/status}; sensor topics
 * ({@code home/sensors/{type}}) carry no room identity.
 */
public record SensorTopic(String roomId, String sensorType) {

    private static final String GATEWAY = "gateway";
    private static final String STATUS = "status";

    public static SensorTopic parse(String topic) {
        String[] parts = topic.split("/", -1);

        if (parts.length == 3 && "home".equals(parts[0])) {
            if ("sensors".equals(parts[1]) && !parts[2].isEmpty() && !STATUS.equals(parts[2])) {
                return new SensorTopic(null, parts[2]);
            }
            if (GATEWAY.equals(parts[1]) && STATUS.equals(parts[2])) {
                return new SensorTopic(GATEWAY, STATUS);
            }
        }

        throw new IllegalArgumentException("Invalid sensor topic: " + topic);
    }
}
