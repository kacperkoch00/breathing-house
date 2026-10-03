package com.breathinghouse.homeapi.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GatewayHeartbeatParser {

    private static final int EXPECTED_SCHEMA_VERSION = 1;
    private static final String EXPECTED_TYPE = "STATUS";

    private final ObjectMapper objectMapper;

    public GatewayHeartbeatParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GatewayHeartbeat parse(byte[] value, String topic, int partition, long offset) {
        JsonNode root;
        try {
            root = objectMapper.readTree(value);
        } catch (IOException ex) {
            throw new NonRetryableHeartbeatException("malformed JSON", ex);
        }

        if (root == null || !root.isObject()) {
            throw new NonRetryableHeartbeatException("payload must be a JSON object");
        }

        JsonNode schemaVersionNode = root.get("schemaVersion");
        if (schemaVersionNode == null || !schemaVersionNode.isNumber()
                || schemaVersionNode.asInt() != EXPECTED_SCHEMA_VERSION) {
            throw new NonRetryableHeartbeatException("schemaVersion must be " + EXPECTED_SCHEMA_VERSION);
        }

        JsonNode typeNode = root.get("type");
        if (typeNode == null || !typeNode.isTextual() || !EXPECTED_TYPE.equals(typeNode.asText())) {
            throw new NonRetryableHeartbeatException("type must be STATUS");
        }

        JsonNode roomIdNode = root.get("roomId");
        if (roomIdNode == null || !roomIdNode.isTextual() || roomIdNode.asText().isBlank()) {
            throw new NonRetryableHeartbeatException("roomId must be a nonblank string");
        }

        Instant observedAt = requireInstant(root.get("observedAt"), "observedAt");
        Instant receivedAt = requireInstant(root.get("receivedAt"), "receivedAt");

        JsonNode valuesNode = root.get("values");
        if (valuesNode == null || !valuesNode.isObject()) {
            throw new NonRetryableHeartbeatException("values must be a JSON object");
        }

        JsonNode statusNode = valuesNode.get("status");
        if (statusNode == null || !statusNode.isTextual() || statusNode.asText().isBlank()) {
            throw new NonRetryableHeartbeatException("values.status must be a nonblank string");
        }

        Map<String, Object> payload = objectMapper.convertValue(valuesNode, new TypeReference<>() {});
        String deviceId = null;
        JsonNode deviceIdNode = root.get("deviceId");
        if (deviceIdNode != null && !deviceIdNode.isNull()) {
            if (!deviceIdNode.isTextual()) {
                throw new NonRetryableHeartbeatException("deviceId must be a string or null");
            }
            deviceId = deviceIdNode.asText();
        }

        return new GatewayHeartbeat(
                roomIdNode.asText(),
                deviceId,
                statusNode.asText(),
                observedAt,
                receivedAt,
                new LinkedHashMap<>(payload),
                topic,
                partition,
                offset);
    }

    private static Instant requireInstant(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            throw new NonRetryableHeartbeatException(fieldName + " must be present");
        }
        if (!node.isTextual()) {
            throw new NonRetryableHeartbeatException(fieldName + " must be an RFC3339 timestamp string");
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException ex) {
            throw new NonRetryableHeartbeatException(fieldName + " must be a parseable RFC3339 timestamp", ex);
        }
    }
}
