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
        log.debug("Transforming presence sensor payload {}", payload);

        try {
            Map<String, Object> root = objectMapper.readValue(payload, new TypeReference<>() {});
            String sensorId = PayloadRules.requireSensorId(root);
            String presence = PayloadRules.requireNonBlank(root, "presence");
            boolean present = isPresent(presence);

            return SensorDataFactory.createSensorData(
                    SensorType.PRESENCE,
                    sensorId,
                    root.get("timestamp"),
                    Map.of("present", present)
            );
        } catch (JsonProcessingException e) {
            throw new InvalidSensorPayloadException("Invalid presence sensor payload", e);
        }
    }

    private static boolean isPresent(String presence) {
        return switch (presence.toUpperCase()) {
            case DETECTED_STATE -> true;
            case CLEAR_STATE -> false;
            default -> throw new InvalidSensorPayloadException("Unknown presence state: " + presence);
        };
    }
}
