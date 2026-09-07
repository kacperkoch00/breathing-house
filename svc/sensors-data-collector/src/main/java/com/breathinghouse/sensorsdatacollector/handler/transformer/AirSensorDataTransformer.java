package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AirSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(AirSensorDataTransformer.class);

    @Override
    public SensorType supportedType() {
        return SensorType.AIR;
    }

    @Override
    public void transform(String payload, String roomId) {
        log.debug("Transforming air sensor payload {} for room: {}", payload, roomId);
    }
}
