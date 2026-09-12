package com.breathinghouse.sensorsdatacollector.handler;

import com.breathinghouse.sensorsdatacollector.handler.transformer.SensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.producer.KafkaProducerConfig;
import com.breathinghouse.sensorsdatacollector.producer.TransformedSensorDataProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class SensorDataHandler {

    private static final Logger log = LoggerFactory.getLogger(SensorDataHandler.class);

    private final Map<SensorType, SensorDataTransformer> transformers;
    private final TransformedSensorDataProducer transformedSensorDataProducer;

    public SensorDataHandler(List<SensorDataTransformer> transformers, TransformedSensorDataProducer transformedSensorDataProducer) {
        this.transformers = new EnumMap<>(SensorType.class);
        this.transformedSensorDataProducer = transformedSensorDataProducer;

        transformers.forEach(transformer ->
                this.transformers.put(transformer.supportedType(), transformer)
        );
    }

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handle(String payload, @Header("mqtt_topic") String topic) {
        SensorTopic sensorTopic;

        try {
            sensorTopic = SensorTopic.parse(topic);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring message with invalid MQTT topic: {}", topic);
            return;
        }

        SensorType sensorType;

        try {
            sensorType = SensorType.from(sensorTopic.sensorType());
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring message with unknown sensor type: {}", sensorTopic.sensorType());
            return;
        }

        SensorDataTransformer transformer = transformers.get(sensorType);

        if (transformer == null) {
            log.warn("No transformer registered for sensor type: {}", sensorType);
            return;
        }

        log.debug(
                "Processing MQTT message. Room ID: {}, Sensor Type: {}, Payload: {}",
                sensorTopic.roomId(),
                sensorType,
                payload
        );

        transformedSensorDataProducer.send(transformer.transform(payload, sensorTopic.roomId()));
    }
}