package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;

public interface SensorDataTransformer {
    SensorType supportedType();

    SensorData transform(String payload, String roomId);
}
