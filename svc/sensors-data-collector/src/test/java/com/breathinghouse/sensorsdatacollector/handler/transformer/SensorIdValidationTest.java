package com.breathinghouse.sensorsdatacollector.handler.transformer;

import com.breathinghouse.sensorsdatacollector.handler.InvalidSensorPayloadException;
import com.breathinghouse.sensorsdatacollector.handler.SensorData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SensorIdValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<SensorDataTransformer, String> BODIES = Map.of(
            new RoomSensorDataTransformer(MAPPER), "\"temperature\":22.5,\"light\":250",
            new AirSensorDataTransformer(MAPPER), "\"temperature\":22.5,\"humidity\":45,\"co2\":650",
            new OpeningSensorDataTransformer(MAPPER), "\"state\":\"OPEN\"",
            new PresenceSensorDataTransformer(MAPPER), "\"presence\":\"DETECTED\""
    );

    static Stream<SensorDataTransformer> transformers() {
        return BODIES.keySet().stream();
    }

    private static String payload(SensorDataTransformer transformer, String sensorIdJson) {
        String body = BODIES.get(transformer);
        return sensorIdJson == null ? "{" + body + "}" : "{\"sensorId\":" + sensorIdJson + "," + body + "}";
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldRejectMissingSensorId(SensorDataTransformer transformer) {
        InvalidSensorPayloadException ex = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload(transformer, null), null)
        );

        assertEquals("Missing required field: sensorId", ex.getMessage());
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldRejectNullSensorId(SensorDataTransformer transformer) {
        InvalidSensorPayloadException ex = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload(transformer, "null"), null)
        );

        assertEquals("Missing required field: sensorId", ex.getMessage());
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldRejectBlankSensorId(SensorDataTransformer transformer) {
        for (String blank : List.of("\"\"", "\"   \"")) {
            InvalidSensorPayloadException ex = assertThrows(
                    InvalidSensorPayloadException.class,
                    () -> transformer.transform(payload(transformer, blank), null)
            );

            assertEquals("Field 'sensorId' must be a non-blank string", ex.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldRejectNonStringSensorId(SensorDataTransformer transformer) {
        for (String nonString : List.of("42", "true", "{\"id\":1}", "[\"a\"]")) {
            InvalidSensorPayloadException ex = assertThrows(
                    InvalidSensorPayloadException.class,
                    () -> transformer.transform(payload(transformer, nonString), null)
            );

            assertEquals("Field 'sensorId' must be a non-blank string", ex.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldRejectSensorIdLongerThan200Characters(SensorDataTransformer transformer) {
        String tooLong = "\"" + "a".repeat(201) + "\"";

        InvalidSensorPayloadException ex = assertThrows(
                InvalidSensorPayloadException.class,
                () -> transformer.transform(payload(transformer, tooLong), null)
        );

        assertEquals("Field 'sensorId' must be at most 200 characters", ex.getMessage());
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldAcceptSensorIdOfExactly200Characters(SensorDataTransformer transformer) {
        String max = "a".repeat(200);

        SensorData result = transformer.transform(payload(transformer, "\"" + max + "\""), null);

        assertEquals(max, result.sensorId());
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldTrimSensorId(SensorDataTransformer transformer) {
        SensorData result = transformer.transform(payload(transformer, "\"  living-air-1  \""), null);

        assertEquals("living-air-1", result.sensorId());
    }

    @ParameterizedTest
    @MethodSource("transformers")
    void shouldBuildSchemaV2EnvelopeWithoutSensorIdOrTimestampInValues(SensorDataTransformer transformer) {
        String body = BODIES.get(transformer);
        String json = "{\"sensorId\":\"s-1\",\"timestamp\":\"2024-01-15T10:30:00Z\"," + body + "}";

        SensorData result = transformer.transform(json, "ignored-room");

        assertEquals(2, result.schemaVersion());
        assertEquals("s-1", result.sensorId());
        assertEquals(transformer.supportedType(), result.type());
        assertEquals(Instant.parse("2024-01-15T10:30:00Z"), result.observedAt());
        assertNull(result.roomId());
        assertNull(result.deviceId());
        assertEquals(false, result.values().containsKey("sensorId"));
        assertEquals(false, result.values().containsKey("timestamp"));
    }

    @Test
    void shouldNotRequireSensorIdForStatus() {
        SensorData result = new StatusSensorDataTransformer(MAPPER)
                .transform("{\"status\":\"ONLINE\"}", "gateway");

        assertNull(result.sensorId());
        assertEquals("gateway", result.roomId());
        assertEquals(1, result.schemaVersion());
    }
}
