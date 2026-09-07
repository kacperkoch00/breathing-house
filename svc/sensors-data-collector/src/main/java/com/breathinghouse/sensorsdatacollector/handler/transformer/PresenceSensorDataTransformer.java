package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PresenceSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(PresenceSensorDataTransformer.class);

    @Override
    public SensorType supportedType() {
        return SensorType.PRESENCE;
    }

    @Override
    public void transform(String payload, String roomId) {
        log.debug("Transforming presence sensor payload {} for room: {}", payload, roomId);
    }
}
