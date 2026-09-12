package com.breathinghouse.sensorsdatacollector.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kafka.producer.topics")
public record KafkaTopicProperties(
        String sensor,
        String event,
        String status
) {
}