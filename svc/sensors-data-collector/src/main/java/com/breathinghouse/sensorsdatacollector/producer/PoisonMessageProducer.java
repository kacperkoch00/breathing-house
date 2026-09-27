package com.breathinghouse.sensorsdatacollector.producer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PoisonMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(PoisonMessageProducer.class);

    private final KafkaTemplate<String, PoisonMessage> kafkaTemplate;
    private final KafkaTopicProperties topicProperties;

    public PoisonMessageProducer(
            KafkaTemplate<String, PoisonMessage> kafkaTemplate,
            KafkaTopicProperties topicProperties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicProperties = topicProperties;
    }

    public void send(PoisonMessage poisonMessage) {
        String topic = topicProperties.dlq();
        String key = poisonMessage.roomId();

        log.debug("Sending poison message to Kafka topic {}: {}", topic, poisonMessage);

        kafkaTemplate.send(topic, key, poisonMessage).whenComplete((result, ex) -> {
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
}
