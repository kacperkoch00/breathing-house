package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class AirSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(AirSensorDataTransformer.class);

    private final ObjectMapper objectMapper;

    public AirSensorDataTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SensorType supportedType() {
        return SensorType.AIR;
    }

    @Override
    public SensorData transform(String payload, String roomId) {
        log.debug("Transforming air sensor payload {} for room: {}", payload, roomId);

        try {
            Map<String, Object> values = objectMapper.readValue(payload, new TypeReference<>() {});
            return new SensorData(roomId, SensorType.AIR, Instant.now(), values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid air sensor payload: " + payload, e);
        }
    }
}