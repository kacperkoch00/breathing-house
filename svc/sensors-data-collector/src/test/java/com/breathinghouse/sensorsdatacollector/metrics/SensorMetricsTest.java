package com.breathinghouse.sensorsdatacollector.metrics;

import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SensorMetricsTest {

    private SimpleMeterRegistry registry;
    private SensorMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SensorMetrics(registry);
    }

    @Test
    void receivedIncrementsCounterForType() {
        metrics.received(SensorType.AIR);
        metrics.received(SensorType.AIR);
        metrics.received(SensorType.ROOM);

        assertEquals(2.0, counterValue(SensorMetrics.RECEIVED, "type", "AIR"));
        assertEquals(1.0, counterValue(SensorMetrics.RECEIVED, "type", "ROOM"));
    }

    @Test
    void publishedIncrementsCounterForType() {
        metrics.published(SensorType.OPENING);

        assertEquals(1.0, counterValue(SensorMetrics.PUBLISHED, "type", "OPENING"));
    }

    @Test
    void rejectedIncrementsCounterForType() {
        metrics.rejected(SensorType.PRESENCE);

        assertEquals(1.0, counterValue(SensorMetrics.REJECTED, "type", "PRESENCE"));
    }

    @Test
    void ignoredIncrementsCounterForReason() {
        metrics.ignored("invalid_topic");
        metrics.ignored("unknown_type");
        metrics.ignored("no_transformer");

        assertEquals(1.0, counterValue(SensorMetrics.IGNORED, "reason", "invalid_topic"));
        assertEquals(1.0, counterValue(SensorMetrics.IGNORED, "reason", "unknown_type"));
        assertEquals(1.0, counterValue(SensorMetrics.IGNORED, "reason", "no_transformer"));
    }

    @Test
    void publishFailedIncrementsCounterForKind() {
        metrics.publishFailed("sensor");
        metrics.publishFailed("dlq");

        assertEquals(1.0, counterValue(SensorMetrics.PUBLISH_FAILED, "kind", "sensor"));
        assertEquals(1.0, counterValue(SensorMetrics.PUBLISH_FAILED, "kind", "dlq"));
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        return registry.counter(name, tagKey, tagValue).count();
    }
}
