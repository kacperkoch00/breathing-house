package com.breathinghouse.sensorsdatacollector.health;

import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
public class HealthController {
    private final Mqtt5AsyncClient hiveMqClient;

    @GetMapping(value = "/live", produces = MediaType.TEXT_PLAIN_VALUE)
    public String live() {
        return "LIVE\n";
    }

    @GetMapping(value = "/ready", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ready() {
        MqttClientState currentState = hiveMqClient.getState();

        if (currentState == MqttClientState.CONNECTED) {
            return ResponseEntity.ok("READY\n");
        }

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("NOT_READY: MQTT consumer state is " + currentState + "\n");
    }
}
