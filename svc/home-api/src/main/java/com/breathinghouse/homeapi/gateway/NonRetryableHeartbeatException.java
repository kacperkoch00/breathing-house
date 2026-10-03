package com.breathinghouse.homeapi.gateway;

public class NonRetryableHeartbeatException extends RuntimeException {

    public NonRetryableHeartbeatException(String message) {
        super(message);
    }

    public NonRetryableHeartbeatException(String message, Throwable cause) {
        super(message, cause);
    }
}
