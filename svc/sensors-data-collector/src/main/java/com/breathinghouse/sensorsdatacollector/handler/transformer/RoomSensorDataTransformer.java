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
public class RoomSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(RoomSensorDataTransformer.class);

    private final ObjectMapper objectMapper;

    public RoomSensorDataTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SensorType supportedType() {
        return SensorType.ROOM;
    }

    @Override
    public SensorData transform(String payload, String roomId) {
        log.debug("Transforming room sensor payload {} for room: {}", payload, roomId);

        try {
            Map<String, Object> values = objectMapper.readValue(payload, new TypeReference<>() {});
            Object lightValue = values.get("light");

            if (lightValue instanceof Number light) {
                values = new java.util.HashMap<>(values);
                values.put("lightLevel", determineLightLevel(light.doubleValue()));
            }

            return new SensorData(roomId, SensorType.ROOM, Instant.now(), values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid room sensor payload: " + payload, e);
        }
    }

    private String determineLightLevel(double lux) {
        if (lux < 10) {
            return "DARK";
        }

        if (lux < 100) {
            return "DIM";
        }

        if (lux < 500) {
            return "NORMAL";
        }

        return "BRIGHT";
    }
}