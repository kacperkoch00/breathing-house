package com.breathinghouse.sensorsdatacollector.integrationtests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class KafkaIntegrationIT {
    private static final String MQTT_HOST = "127.0.0.1";
    private static final int MQTT_PORT = 1883;
    private static final String KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:9092";

    private Mqtt5AsyncClient publisher;
    private KafkaConsumer<String, String> consumer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        publisher = MqttClient.builder()
                .useMqttVersion5()
                .identifier("integration-test-publisher-" + UUID.randomUUID())
                .serverHost(MQTT_HOST)
                .serverPort(MQTT_PORT)
                .buildAsync();

        publisher.connect().join();

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "integration-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        consumer = new KafkaConsumer<>(properties);
    }

    @AfterEach
    void tearDown() {
        consumer.close();
        publisher.disconnect().join();
    }

    @ParameterizedTest
    @CsvSource({
            "home/kitchen/room,     sensor-data, '{\"temperature\":22.5,\"light\":250}', ROOM",
            "home/kitchen/air,      sensor-data, '{\"temperature\":22.5}', AIR",
            "home/kitchen/opening,  event-data,  '{\"state\":\"OPEN\"}', OPENING",
            "home/kitchen/presence, event-data,  '{\"presence\":\"DETECTED\"}', PRESENCE",
            "home/gateway/status,   status-data,  '{\"status\":\"ONLINE\"}', STATUS"
    })
    void shouldTransformMqttMessageAndPublishToKafka(
            String mqttTopic,
            String kafkaTopic,
            String payload,
            String sensorType
    ) throws Exception {
        consumer.subscribe(Collections.singletonList(kafkaTopic));

        consumer.poll(Duration.ofSeconds(1));

        publisher.publishWith()
                .topic(mqttTopic)
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .send()
                .join();

        ConsumerRecord<String, String> record = awaitKafkaRecord(kafkaTopic);

        JsonNode message = objectMapper.readTree(record.value());

        assertThat(record.topic()).isEqualTo(kafkaTopic);
        assertThat(record.key()).isEqualTo(mqttTopic.equals("home/gateway/status") ? "gateway" : "kitchen");
        assertThat(message.get("roomId").asText()).isEqualTo(mqttTopic.equals("home/gateway/status") ? "gateway" : "kitchen");
        assertThat(message.get("type").asText()).isEqualTo(sensorType);
        assertThat(message.get("timestamp")).isNotNull();
        assertThat(message.get("values")).isNotNull();
    }

    private ConsumerRecord<String, String> awaitKafkaRecord(String topic) {
        final ConsumerRecord<?, ?>[] result = new ConsumerRecord<?, ?>[1];

        await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> {
                    var records = consumer.poll(Duration.ofMillis(500));

                    for (ConsumerRecord<String, String> record : records) {
                        if (record.topic().equals(topic)) {
                            result[0] = record;
                            return true;
                        }
                    }

                    return false;
                });

        @SuppressWarnings("unchecked")
        ConsumerRecord<String, String> record = (ConsumerRecord<String, String>) result[0];

        return record;
    }
}