package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.InvalidSensorPayloadException;
import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
        log.debug("Transforming opening sensor payload {}", payload);

        try {
            Map<String, Object> root = objectMapper.readValue(payload, new TypeReference<>() {});
            String sensorId = PayloadRules.requireSensorId(root);
            String state = PayloadRules.requireNonBlank(root, "state");
            return SensorDataFactory.createSensorData(
                    SensorType.OPENING,
                    sensorId,
                    root.get("timestamp"),
                    Map.of("open", isOpen(state))
            );
        } catch (JsonProcessingException e) {
            throw new InvalidSensorPayloadException("Invalid opening sensor payload", e);
        }
    }

    private static boolean isOpen(String state) {
        return switch (state.toUpperCase()) {
            case OPEN_STATE -> true;
            case CLOSED_STATE -> false;
            default -> throw new InvalidSensorPayloadException("Unknown opening state: " + state);
        };
    }
}
