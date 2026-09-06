package com.breathinghouse.sensorsdatacollector.consumer;

import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SensorDataHandler {

    private static final Logger log = LoggerFactory.getLogger(SensorDataHandler.class);

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handle(String payload, @Header("mqtt_topic") String topic) {
        log.debug("Processing MQTT message. Topic: {}, Payload: {}", topic, payload);
    }
}
