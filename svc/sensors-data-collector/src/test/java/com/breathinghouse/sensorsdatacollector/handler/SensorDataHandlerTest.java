package com.breathinghouse.sensorsdatacollector.handler;

import com.breathinghouse.sensorsdatacollector.handler.transformer.AirSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.OpeningSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.PresenceSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.RoomSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.SensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.handler.transformer.StatusSensorDataTransformer;
import com.breathinghouse.sensorsdatacollector.metrics.SensorMetrics;
import com.breathinghouse.sensorsdatacollector.producer.PoisonMessage;
import com.breathinghouse.sensorsdatacollector.producer.PoisonMessageProducer;
import com.breathinghouse.sensorsdatacollector.producer.TransformedSensorDataProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SensorDataHandlerTest {

    private SensorDataHandler handler;
    private TransformedSensorDataProducer producer;
    private PoisonMessageProducer poisonMessageProducer;
    private SimpleMeterRegistry meterRegistry;
    private SensorMetrics metrics;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        producer = Mockito.mock(TransformedSensorDataProducer.class);
        poisonMessageProducer = Mockito.mock(PoisonMessageProducer.class);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new SensorMetrics(meterRegistry);

        List<SensorDataTransformer> transformers = List.of(
                new RoomSensorDataTransformer(mapper),
                new AirSensorDataTransformer(mapper),
                new OpeningSensorDataTransformer(mapper),
                new PresenceSensorDataTransformer(mapper),
                new StatusSensorDataTransformer(mapper)
        );

        handler = new SensorDataHandler(transformers, producer, poisonMessageProducer, metrics);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "room",
            "air",
            "opening",
            "presence",
            "status"
    })
    void shouldHandleAllSensorTypes(String sensorType) {
        Map<String, String> payloads = Map.of(
                "room", "{\"temperature\":22.5,\"light\":250}",
                "air", "{\"temperature\":22.5,\"humidity\":45,\"co2\":650}",
                "opening", "{\"state\": \"open\"}",
                "presence", "{\"presence\": \"detected\"}",
                "status", "{\"status\":\"ONLINE\"}");

        assertDoesNotThrow(() ->
                handler.handle(payloads.get(sensorType), "home/kitchen/" + sensorType)
        );

        verify(producer).send(any(SensorData.class));
        verify(poisonMessageProducer, never()).send(any());
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.RECEIVED, "type", SensorType.from(sensorType).name()).count());
        assertEquals(0.0, meterRegistry.counter(SensorMetrics.REJECTED, "type", SensorType.from(sensorType).name()).count());
    }

    @Test
    void shouldPublishInvalidPayloadToDlq() {
        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/air")
        );

        verify(producer, never()).send(any());

        ArgumentCaptor<PoisonMessage> captor = ArgumentCaptor.forClass(PoisonMessage.class);
        verify(poisonMessageProducer).send(captor.capture());

        PoisonMessage poisonMessage = captor.getValue();
        assertEquals("kitchen", poisonMessage.roomId());
        assertEquals("AIR", poisonMessage.sensorType());
        assertEquals("{}", poisonMessage.payload());
        assertEquals("home/kitchen/air", poisonMessage.mqttTopic());
        assertFalse(poisonMessage.reason() == null || poisonMessage.reason().isBlank());
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.RECEIVED, "type", "AIR").count());
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.REJECTED, "type", "AIR").count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "invalid",
            "home",
            "home/kitchen",
            "sensors/kitchen/air",
            "home/kitchen/air/invalid"
    })
    void shouldIgnoreInvalidTopics(String topic) {
        assertDoesNotThrow(() ->
                handler.handle("{}", topic)
        );

        verify(producer, never()).send(any());
        verify(poisonMessageProducer, never()).send(any());
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.IGNORED, "reason", "invalid_topic").count());
        assertEquals(0.0, meterRegistry.find(SensorMetrics.RECEIVED).counters().stream()
                .mapToDouble(c -> c.count())
                .sum());
    }

    @Test
    void shouldIgnoreUnknownSensorType() {
        assertDoesNotThrow(() ->
                handler.handle("{}", "home/kitchen/unknown")
        );

        verify(producer, never()).send(any());
        verify(poisonMessageProducer, never()).send(any());
        assertEquals(1.0, meterRegistry.counter(SensorMetrics.IGNORED, "reason", "unknown_type").count());
    }

    @Test
    void shouldIgnoreSensorTypeWithoutTransformer() {
        SensorDataTransformer transformer = new SensorDataTransformer() {
            @Override
            public SensorType supportedType() {
                return SensorType.ROOM;
            }

            @Override
            public SensorData transform(String payload, String roomId) {
                Instant now = Instant.now();
                return new SensorData(
                        SensorData.SCHEMA_VERSION,
                        roomId,
                        null,
                        SensorType.ROOM,
                        now,
                        now,
                        Map.of()
                );
            }
        };

        SimpleMeterRegistry localRegistry = new SimpleMeterRegistry();
        SensorMetrics localMetrics = new SensorMetrics(localRegistry);
        TransformedSensorDataProducer producerWithoutAir = Mockito.mock(TransformedSensorDataProducer.class);
        PoisonMessageProducer poisonWithoutAir = Mockito.mock(PoisonMessageProducer.class);
        SensorDataHandler handlerWithoutAir = new SensorDataHandler(
                List.of(transformer),
                producerWithoutAir,
                poisonWithoutAir,
                localMetrics
        );

        assertDoesNotThrow(() ->
                handlerWithoutAir.handle("{}", "home/kitchen/air")
        );

        verify(producerWithoutAir, never()).send(any());
        verify(poisonWithoutAir, never()).send(any());
        assertEquals(1.0, localRegistry.counter(SensorMetrics.IGNORED, "reason", "no_transformer").count());
    }
}
