package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;

public interface SensorDataTransformer {
    SensorType supportedType();

    /**
     * @param roomId room identity from the MQTT topic; only non-null for {@code STATUS}
     *               (sensor topics carry none, sensors are identified by {@code sensorId} in the payload)
     */
    SensorData transform(String payload, String roomId);
}
