package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RoomSensorDataTransformer implements SensorDataTransformer {
    private static final Logger log = LoggerFactory.getLogger(RoomSensorDataTransformer.class);

    @Override
    public SensorType supportedType() {
        return SensorType.ROOM;
    }

    @Override
    public void transform(String payload, String roomId) {
        log.debug("Transforming room sensor payload {} for room: {}", payload, roomId);
    }
}
