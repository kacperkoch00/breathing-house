package com.breathinghouse.homeapi.alerts;

public class AlertNotFoundException extends RuntimeException {

    private final long alertId;

    public AlertNotFoundException(long alertId) {
        super("Alert '" + alertId + "' was not found");
        this.alertId = alertId;
    }

    public long alertId() {
        return alertId;
    }
}
