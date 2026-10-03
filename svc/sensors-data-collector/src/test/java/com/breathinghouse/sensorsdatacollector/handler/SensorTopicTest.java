package com.breathinghouse.sensorsdatacollector.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SensorTopicTest {

    @ParameterizedTest
    @ValueSource(strings = {"room", "air", "opening", "presence"})
    void shouldParseSensorTopicsWithoutRoomId(String type) {
        SensorTopic topic = SensorTopic.parse("home/sensors/" + type);

        assertNull(topic.roomId());
        assertEquals(type, topic.sensorType());
    }

    @Test
    void shouldParseGatewayStatusTopic() {
        SensorTopic topic = SensorTopic.parse("home/gateway/status");

        assertEquals("gateway", topic.roomId());
        assertEquals("status", topic.sensorType());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "home",
            "home/sensors",
            "home/sensors/",
            "home/sensors/status",
            "home/sensors/air/extra",
            "home/kitchen/air",
            "home/gateway/air",
            "sensors/sensors/air"
    })
    void shouldRejectInvalidTopics(String topic) {
        assertThrows(IllegalArgumentException.class, () -> SensorTopic.parse(topic));
    }
}
