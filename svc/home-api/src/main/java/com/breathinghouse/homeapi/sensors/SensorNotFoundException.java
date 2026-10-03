package com.breathinghouse.homeapi.sensors;

public class SensorNotFoundException extends RuntimeException {

    private final String sensorId;

    public SensorNotFoundException(String sensorId) {
        super("Sensor '" + sensorId + "' was not found");
        this.sensorId = sensorId;
    }

    public String sensorId() {
        return sensorId;
    }
}
