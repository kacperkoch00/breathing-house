package com.breathinghouse.homeapi.sensors;

public class SensorAssignmentConflictException extends RuntimeException {

    public SensorAssignmentConflictException(String sensorId, String roomId) {
        super("Sensor '" + sensorId + "' is not currently assigned to room '" + roomId + "'");
    }
}
