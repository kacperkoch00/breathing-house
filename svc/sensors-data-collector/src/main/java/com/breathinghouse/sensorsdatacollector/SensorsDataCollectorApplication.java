package com.breathinghouse.sensorsdatacollector;

import com.breathinghouse.sensorsdatacollector.producer.KafkaTopicProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class SensorsDataCollectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SensorsDataCollectorApplication.class, args);
    }
}
