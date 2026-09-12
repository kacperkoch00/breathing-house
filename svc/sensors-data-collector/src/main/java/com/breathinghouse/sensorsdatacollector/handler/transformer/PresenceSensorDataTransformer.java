package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class PresenceSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(PresenceSensorDataTransformer.class);

    private final ObjectMapper objectMapper;

    private static final String DETECTED_STATE = "DETECTED";
    private static final String CLEAR_STATE = "CLEAR";

    public PresenceSensorDataTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SensorType supportedType() {
        return SensorType.PRESENCE;
    }

    @Override
    public SensorData transform(String payload, String roomId) {
        log.debug("Transforming presence sensor payload {} for room: {}", payload, roomId);

        try {
            JsonNode root = objectMapper.readTree(payload);
            String presence = root.path("presence").asText();

            boolean present = isPresent(presence);

            return new SensorData(roomId, SensorType.PRESENCE, Instant.now(), Map.of("present", present));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid presence sensor payload: " + payload, e);
        }
    }

    private static boolean isPresent(String presence) {
        return switch (presence.toUpperCase()) {
            case DETECTED_STATE -> true;
            case CLEAR_STATE -> false;
            default -> throw new IllegalArgumentException("Unknown presence state: " + presence);
        };
    }
}