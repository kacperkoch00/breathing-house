package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.SensorType;

public interface SensorDataTransformer {
    SensorType supportedType();

    void transform(String payload, String roomId);
}
