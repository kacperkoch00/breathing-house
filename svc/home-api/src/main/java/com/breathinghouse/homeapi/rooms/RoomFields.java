package com.breathinghouse.homeapi.rooms;

import com.fasterxml.jackson.databind.JsonNode;

final class RoomFields {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private RoomFields() {
    }

    static String requireName(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("name must be provided");
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("name must be a string");
        }
        return normalizeName(node.asText());
    }

    static String optionalDescription(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("description must be a string or null");
        }
        return normalizeDescription(node.asText());
    }

    static String normalizeName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    static String normalizeDescription(String description) {
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed;
    }
}
