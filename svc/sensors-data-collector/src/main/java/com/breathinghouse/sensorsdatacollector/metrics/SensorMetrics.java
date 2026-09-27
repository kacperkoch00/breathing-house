package com.breathinghouse.sensorsdatacollector.metrics;

import com.breathinghouse.sensorsdatacollector.handler.SensorType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SensorMetrics {

    public static final String RECEIVED = "sensor.messages.received";
    public static final String PUBLISHED = "sensor.messages.published";
    public static final String REJECTED = "sensor.messages.rejected";
    public static final String IGNORED = "sensor.messages.ignored";
    public static final String PUBLISH_FAILED = "sensor.publish.failed";

    private final MeterRegistry registry;

    public SensorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void received(SensorType type) {
        registry.counter(RECEIVED, "type", type.name()).increment();
    }

    public void published(SensorType type) {
        registry.counter(PUBLISHED, "type", type.name()).increment();
    }

    public void rejected(SensorType type) {
        registry.counter(REJECTED, "type", type.name()).increment();
    }

    public void ignored(String reason) {
        registry.counter(IGNORED, "reason", reason).increment();
    }

    public void publishFailed(String kind) {
        registry.counter(PUBLISH_FAILED, "kind", kind).increment();
    }
}
