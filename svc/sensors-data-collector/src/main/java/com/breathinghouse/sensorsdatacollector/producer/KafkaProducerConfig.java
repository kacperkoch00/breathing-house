package com.breathinghouse.sensorsdatacollector.producer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, SensorData> producerFactory(KafkaProperties kafkaProperties) {
        return new DefaultKafkaProducerFactory<>(
                producerConfigs(kafkaProperties),
                new StringSerializer(),
                kafkaJsonSerializer()
        );
    }

    @Bean
    public KafkaTemplate<String, SensorData> kafkaTemplate(
            ProducerFactory<String, SensorData> producerFactory
    ) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ProducerFactory<String, PoisonMessage> poisonMessageProducerFactory(
            KafkaProperties kafkaProperties
    ) {
        return new DefaultKafkaProducerFactory<>(
                producerConfigs(kafkaProperties),
                new StringSerializer(),
                kafkaJsonSerializer()
        );
    }

    @Bean
    public KafkaTemplate<String, PoisonMessage> poisonMessageKafkaTemplate(
            ProducerFactory<String, PoisonMessage> poisonMessageProducerFactory
    ) {
        return new KafkaTemplate<>(poisonMessageProducerFactory);
    }

    @Bean(destroyMethod = "close")
    public AdminClient kafkaAdminClient(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaProperties.getBootstrapServers()
        );
        return AdminClient.create(properties);
    }

    static ObjectMapper kafkaObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    static <T> JsonSerializer<T> kafkaJsonSerializer() {
        JsonSerializer<T> serializer = new JsonSerializer<>(kafkaObjectMapper());
        serializer.setAddTypeInfo(false);
        return serializer;
    }

    private static Map<String, Object> producerConfigs(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaProperties.getBootstrapServers()
        );
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return properties;
    }
}
