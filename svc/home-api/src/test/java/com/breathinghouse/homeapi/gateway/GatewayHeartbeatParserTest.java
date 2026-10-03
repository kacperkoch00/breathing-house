package com.breathinghouse.homeapi.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayHeartbeatParserTest {

    private GatewayHeartbeatParser parser;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        parser = new GatewayHeartbeatParser(objectMapper);
    }

    @Test
    void parsesValidStatusEnvelope() {
        GatewayHeartbeat heartbeat = parser.parse(validPayload().getBytes(StandardCharsets.UTF_8), "status-data", 0, 7);

        assertThat(heartbeat.gatewayId()).isEqualTo("gateway");
        assertThat(heartbeat.deviceId()).isNull();
        assertThat(heartbeat.reportedStatus()).isEqualTo("ONLINE");
        assertThat(heartbeat.observedAt()).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
        assertThat(heartbeat.receivedAt()).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
        assertThat(heartbeat.payload())
                .containsEntry("status", "ONLINE")
                .containsEntry("uptime", 3600)
                .containsEntry("connected", true);
        assertThat(heartbeat.kafkaTopic()).isEqualTo("status-data");
        assertThat(heartbeat.kafkaPartition()).isEqualTo(0);
        assertThat(heartbeat.kafkaOffset()).isEqualTo(7L);
    }

    @Test
    void preservesArbitraryValuesFields() {
        String payload = """
                {
                  "schemaVersion": 1,
                  "roomId": "gateway",
                  "deviceId": "gw-1",
                  "type": "STATUS",
                  "observedAt": "2026-10-03T10:00:00Z",
                  "receivedAt": "2026-10-03T10:00:01Z",
                  "values": {
                    "status": "READY",
                    "customFlag": true,
                    "nested": {"a": 1}
                  }
                }
                """;

        GatewayHeartbeat heartbeat = parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 1, 2);

        assertThat(heartbeat.deviceId()).isEqualTo("gw-1");
        assertThat(heartbeat.reportedStatus()).isEqualTo("READY");
        assertThat(heartbeat.payload())
                .containsEntry("status", "READY")
                .containsEntry("customFlag", true)
                .containsKey("nested");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{".getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("malformed JSON");
    }

    @Test
    void rejectsWrongSchemaVersion() {
        String payload = validPayload().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
        assertThatThrownBy(() -> parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsWrongType() {
        String payload = validPayload().replace("\"STATUS\"", "\"AIR\"");
        assertThatThrownBy(() -> parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("type");
    }

    @Test
    void rejectsMissingGatewayId() {
        String payload = validPayload().replace("\"roomId\": \"gateway\",", "");
        assertThatThrownBy(() -> parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("roomId");
    }

    @Test
    void rejectsBlankGatewayId() {
        String payload = validPayload().replace("\"gateway\"", "\"  \"");
        assertThatThrownBy(() -> parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("roomId");
    }

    @Test
    void rejectsMissingStatus() {
        String payload = """
                {
                  "schemaVersion": 1,
                  "roomId": "gateway",
                  "deviceId": null,
                  "type": "STATUS",
                  "observedAt": "2026-10-03T10:00:00Z",
                  "receivedAt": "2026-10-03T10:00:00Z",
                  "values": { "uptime": 1 }
                }
                """;
        assertThatThrownBy(() -> parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("values.status");
    }

    @Test
    void rejectsNonStringStatus() {
        String payload = """
                {
                  "schemaVersion": 1,
                  "roomId": "gateway",
                  "deviceId": null,
                  "type": "STATUS",
                  "observedAt": "2026-10-03T10:00:00Z",
                  "receivedAt": "2026-10-03T10:00:00Z",
                  "values": { "status": true }
                }
                """;
        assertThatThrownBy(() -> parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("values.status");
    }

    @Test
    void rejectsBlankStatus() {
        String payload = validPayload().replace("\"ONLINE\"", "\"\"");
        assertThatThrownBy(() -> parser.parse(payload.getBytes(StandardCharsets.UTF_8), "status-data", 0, 1))
                .isInstanceOf(NonRetryableHeartbeatException.class)
                .hasMessageContaining("values.status");
    }

    private static String validPayload() {
        return """
                {
                  "schemaVersion": 1,
                  "roomId": "gateway",
                  "deviceId": null,
                  "type": "STATUS",
                  "observedAt": "2026-10-03T10:00:00Z",
                  "receivedAt": "2026-10-03T10:00:00Z",
                  "values": {
                    "status": "ONLINE",
                    "uptime": 3600,
                    "connected": true
                  }
                }
                """;
    }
}
