package com.breathinghouse.sensorsdatacollector.producer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransformedSensorDataProducer {

    private static final Logger log = LoggerFactory.getLogger(TransformedSensorDataProducer.class);

    private final KafkaTemplate<String, SensorData> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;

    public TransformedSensorDataProducer(
            KafkaTemplate<String, SensorData> kafkaTemplate,
            KafkaTopicProperties topicProperties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicProperties = topicProperties;
    }

    public void send(SensorData sensorData) {
        String topic = getTopic(sensorData.type());
        String key = sensorData.roomId();

        log.debug("Sending transformed sensor data to Kafka topic {}: {}", topic, sensorData);

        kafkaTemplate.send(topic, key, sensorData).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to Kafka topic {} key {}", topic, key, ex);
                return;
            }
            log.debug(
                    "Published to Kafka topic {} partition {} offset {}",
                    topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset()
            );
        });
    }

    private String getTopic(SensorType type) {
        return switch (type) {
            case ROOM, AIR -> topicProperties.sensor();
            case OPENING, PRESENCE -> topicProperties.event();
            case STATUS -> topicProperties.status();
        };
    }
}
