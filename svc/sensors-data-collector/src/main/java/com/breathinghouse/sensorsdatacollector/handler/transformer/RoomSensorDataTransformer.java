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

import java.util.HashMap;
import java.util.Map;

@Component
public class RoomSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(RoomSensorDataTransformer.class);

    private static final double TEMPERATURE_MIN = -40;
    private static final double TEMPERATURE_MAX = 80;
    private static final double LIGHT_MIN = 0;

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
        log.debug("Transforming room sensor payload {}", payload);

        try {
            Map<String, Object> values = objectMapper.readValue(payload, new TypeReference<>() {});
            String sensorId = PayloadRules.requireSensorId(values);
            double temperature = PayloadRules.requireNumber(values, "temperature");
            PayloadRules.requireInRange(temperature, TEMPERATURE_MIN, TEMPERATURE_MAX, "temperature");

            double light = PayloadRules.requireNumber(values, "light");
            PayloadRules.requireInRange(light, LIGHT_MIN, Double.POSITIVE_INFINITY, "light");

            values = new HashMap<>(values);
            values.put("lightLevel", determineLightLevel(light));

            return SensorDataFactory.createSensorData(SensorType.ROOM, sensorId, values.get("timestamp"), values);
        } catch (JsonProcessingException e) {
            throw new InvalidSensorPayloadException("Invalid room sensor payload", e);
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
