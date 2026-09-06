package com.breathinghouse.sensorsdatacollector.consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SensorDataHandlerTest {

    private final SensorDataHandler handler = new SensorDataHandler();

    @Test
    void shouldHandleSensorData() {
        assertDoesNotThrow(() -> handler.handle("{\"temperature\":22.5}", "home/kitchen/air"));
    }
}