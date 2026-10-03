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
import org.junit.jupiter.api.Test;
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
            "home/sensors/room,     sensor-data, '{\"sensorId\":\"room-1\",\"temperature\":22.5,\"light\":250}', ROOM",
            "home/sensors/air,      sensor-data, '{\"sensorId\":\"air-1\",\"temperature\":22.5,\"humidity\":45,\"co2\":650}', AIR",
            "home/sensors/opening,  event-data,  '{\"sensorId\":\"door-1\",\"state\":\"OPEN\"}', OPENING",
            "home/sensors/presence, event-data,  '{\"sensorId\":\"pir-1\",\"presence\":\"DETECTED\"}', PRESENCE"
    })
    void shouldTransformSensorMessageToSchemaV2AndPublishToKafka(
            String mqttTopic,
            String kafkaTopic,
            String payload,
            String sensorType
    ) throws Exception {
        String sensorId = objectMapper.readTree(payload).get("sensorId").asText();

        consumer.subscribe(Collections.singletonList(kafkaTopic));

        consumer.poll(Duration.ofSeconds(1));

        publish(mqttTopic, payload);

        ConsumerRecord<String, String> record = awaitKafkaRecord(kafkaTopic, sensorId);

        JsonNode message = objectMapper.readTree(record.value());

        assertThat(record.topic()).isEqualTo(kafkaTopic);
        assertThat(record.key()).isEqualTo(sensorId);
        assertThat(message.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(message.get("sensorId").asText()).isEqualTo(sensorId);
        assertThat(message.has("roomId")).isFalse();
        assertThat(message.has("deviceId")).isFalse();
        assertThat(message.get("type").asText()).isEqualTo(sensorType);
        assertThat(message.get("observedAt").isTextual()).isTrue();
        assertThat(message.get("receivedAt").isTextual()).isTrue();
        assertThat(message.get("observedAt").asText()).endsWith("Z");
        assertThat(message.get("receivedAt").asText()).endsWith("Z");
        assertThat(message.get("values")).isNotNull();
        assertThat(message.get("values").has("sensorId")).isFalse();
        assertThat(message.get("values").has("timestamp")).isFalse();
    }

    @Test
    void shouldKeepStatusAsSchemaV1() throws Exception {
        consumer.subscribe(Collections.singletonList("status-data"));

        consumer.poll(Duration.ofSeconds(1));

        publish("home/gateway/status", "{\"status\":\"ONLINE\"}");

        ConsumerRecord<String, String> record = awaitKafkaRecord("status-data", null);

        JsonNode message = objectMapper.readTree(record.value());

        assertThat(record.key()).isEqualTo("gateway");
        assertThat(message.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(message.get("roomId").asText()).isEqualTo("gateway");
        assertThat(message.has("sensorId")).isFalse();
        assertThat(message.get("type").asText()).isEqualTo("STATUS");
        assertThat(message.get("observedAt").isTextual()).isTrue();
        assertThat(message.get("receivedAt").isTextual()).isTrue();
        assertThat(message.get("observedAt").asText()).endsWith("Z");
        assertThat(message.get("receivedAt").asText()).endsWith("Z");
        assertThat(message.get("values")).isNotNull();
    }

    private void publish(String topic, String payload) {
        publisher.publishWith()
                .topic(topic)
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .send()
                .join();
    }

    private ConsumerRecord<String, String> awaitKafkaRecord(String topic, String expectedKey) {
        final ConsumerRecord<?, ?>[] result = new ConsumerRecord<?, ?>[1];

        await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> {
                    var records = consumer.poll(Duration.ofMillis(500));

                    for (ConsumerRecord<String, String> record : records) {
                        if (record.topic().equals(topic) && (expectedKey == null || expectedKey.equals(record.key()))) {
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