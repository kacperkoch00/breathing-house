package com.breathinghouse.sensorsdatacollector.consumer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Configuration
@ConfigurationProperties(prefix = "mqtt")
public class SensorDataConsumerConfig {
    private String brokerIp;
    private Integer brokerPort;
    private String clientId;
    private List<String> consumerTopics;
    private Integer initialDelayMs;
    private Integer maxDelayMs;
}
