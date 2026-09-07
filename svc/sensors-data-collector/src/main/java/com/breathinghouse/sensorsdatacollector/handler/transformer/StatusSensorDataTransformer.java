package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StatusSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(StatusSensorDataTransformer.class);

    @Override
    public SensorType supportedType() {
        return SensorType.STATUS;
    }

    @Override
    public void transform(String payload, String roomId) {
        log.debug("Transforming status sensor payload {} for room: {}", payload, roomId);
    }
}
