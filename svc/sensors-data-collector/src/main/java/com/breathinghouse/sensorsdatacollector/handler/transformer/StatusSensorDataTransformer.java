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
public class StatusSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(StatusSensorDataTransformer.class);

    private final ObjectMapper objectMapper;

    public StatusSensorDataTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SensorType supportedType() {
        return SensorType.STATUS;
    }

    @Override
    public SensorData transform(String payload, String roomId) {
        log.debug("Transforming status sensor payload {}", payload);

        try {
            Map<String, Object> values = objectMapper.readValue(payload, new TypeReference<>() {});
            return new SensorData(roomId, SensorType.STATUS, Instant.now(), values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid status sensor payload: " + payload, e);
        }
    }
}