package com.breathinghouse.homeapi.sensors;

import java.util.List;

public record SensorSummary(String sensorId, String displayName, List<String> types, String roomId) {
}
