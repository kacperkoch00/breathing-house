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
public class AirSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(AirSensorDataTransformer.class);

    private static final double TEMPERATURE_MIN = -40;
    private static final double TEMPERATURE_MAX = 80;
    private static final double HUMIDITY_MIN = 0;
    private static final double HUMIDITY_MAX = 100;
    private static final double CO2_MIN = 0;
    private static final double CO2_MAX = 10_000;

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

            double temperature = PayloadRules.requireNumber(values, "temperature");
            PayloadRules.requireInRange(temperature, TEMPERATURE_MIN, TEMPERATURE_MAX, "temperature");

            double humidity = PayloadRules.requireNumber(values, "humidity");
            PayloadRules.requireInRange(humidity, HUMIDITY_MIN, HUMIDITY_MAX, "humidity");

            double co2 = PayloadRules.requireNumber(values, "co2");
            PayloadRules.requireInRange(co2, CO2_MIN, CO2_MAX, "co2");

            return SensorDataFactory.create(roomId, SensorType.AIR, values);
        } catch (JsonProcessingException e) {
            throw new InvalidSensorPayloadException("Invalid air sensor payload", e);
        }
    }
}
