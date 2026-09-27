package com.breathinghouse.sensorsdatacollector.handler;

public class InvalidSensorPayloadException extends RuntimeException {

    public InvalidSensorPayloadException(String message) {
        super(message);
    }

    public InvalidSensorPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
