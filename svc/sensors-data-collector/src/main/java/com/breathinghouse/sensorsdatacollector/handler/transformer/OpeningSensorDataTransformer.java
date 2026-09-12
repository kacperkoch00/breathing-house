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
public class OpeningSensorDataTransformer implements SensorDataTransformer {

    private static final Logger log = LoggerFactory.getLogger(OpeningSensorDataTransformer.class);

    private final ObjectMapper objectMapper;

    private static final String OPEN_STATE = "OPEN";
    private static final String CLOSED_STATE = "CLOSED";

    public OpeningSensorDataTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SensorType supportedType() {
        return SensorType.OPENING;
    }

    @Override
    public SensorData transform(String payload, String roomId) {
        log.debug("Transforming opening sensor payload {} for room: {}", payload, roomId);

        try {
            JsonNode root = objectMapper.readTree(payload);
            String state = root.path("state").asText();
            return new SensorData(roomId, SensorType.OPENING, Instant.now(), Map.of("open", isOpen(state)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid opening sensor payload: " + payload, e);
        }
    }

    private static boolean isOpen(String state) {
        return switch (state.toUpperCase()) {
            case OPEN_STATE -> true;
            case CLOSED_STATE -> false;
            default -> throw new IllegalArgumentException("Unknown opening state: " + state);
        };
    }
}