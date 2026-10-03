package com.breathinghouse.sensorsdatacollector.producer;

import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerSerializationTest {

    private static final Instant FIXED = Instant.parse("2026-10-03T11:09:07.401117555Z");

    @Test
    void shouldSerializeSensorDataInstantsAsRfc3339Strings() throws Exception {
        SensorData sensorData = new SensorData(
                SensorData.SCHEMA_VERSION,
                "e2e-air-1",
                null,
                null,
                SensorType.AIR,
                FIXED,
                FIXED,
                Map.of("temperature", 22.5, "humidity", 89, "co2", 1000)
        );

        String json = serialize(sensorData);

        assertThat(json).contains("\"observedAt\":\"2026-10-03T11:09:07.401117555Z\"");
        assertThat(json).contains("\"receivedAt\":\"2026-10-03T11:09:07.401117555Z\"");
        assertThat(json).doesNotContain("\"observedAt\":1");
        assertThat(json).doesNotContain("\"receivedAt\":1");

        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.get("observedAt").isTextual()).isTrue();
        assertThat(node.get("receivedAt").isTextual()).isTrue();
        assertThat(node.get("observedAt").asText()).endsWith("Z");
        assertThat(node.get("receivedAt").asText()).endsWith("Z");
    }

    @Test
    void shouldSerializeStatusInstantsAsRfc3339Strings() throws Exception {
        SensorData status = new SensorData(
                SensorData.STATUS_SCHEMA_VERSION,
                null,
                "gateway",
                null,
                SensorType.STATUS,
                FIXED,
                FIXED,
                Map.of("status", "ONLINE")
        );

        String json = serialize(status);

        assertThat(json).contains("\"schemaVersion\":1");
        assertThat(json).contains("\"observedAt\":\"2026-10-03T11:09:07.401117555Z\"");
        assertThat(json).contains("\"receivedAt\":\"2026-10-03T11:09:07.401117555Z\"");

        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.get("observedAt").isTextual()).isTrue();
        assertThat(node.get("receivedAt").isTextual()).isTrue();
    }

    @Test
    void shouldSerializePoisonMessageRejectedAtAsRfc3339String() throws Exception {
        PoisonMessage poisonMessage = new PoisonMessage(
                FIXED,
                "home/sensors/air",
                null,
                "AIR",
                "invalid",
                "{}"
        );

        JsonSerializer<PoisonMessage> serializer = KafkaProducerConfig.kafkaJsonSerializer();
        byte[] bytes = serializer.serialize("sensor-data-dlq", poisonMessage);
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\"rejectedAt\":\"2026-10-03T11:09:07.401117555Z\"");
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.get("rejectedAt").isTextual()).isTrue();
    }

    private static String serialize(SensorData sensorData) {
        JsonSerializer<SensorData> serializer = KafkaProducerConfig.kafkaJsonSerializer();
        byte[] bytes = serializer.serialize("sensor-data", sensorData);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
