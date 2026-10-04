package com.breathinghouse.homeapi.history;

import com.breathinghouse.homeapi.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HistoryController.class)
@Import({HistoryExceptionHandler.class, CorsConfig.class})
class HistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HistoryRepository historyRepository;

    @Test
    void environmentHistoryReturnsMappedFieldsAndNulls() throws Exception {
        when(historyRepository.findEnvironmentReadings(
                eq("living-room"), isNull(), isNull(), isNull(), isNull(), eq(100), eq(0)))
                .thenReturn(new PageResponse<>(List.of(new EnvironmentReading(
                        123L,
                        "living-room",
                        "air-1",
                        SensorType.AIR,
                        22.5,
                        45.0,
                        700.0,
                        null,
                        null,
                        Instant.parse("2026-10-03T08:00:00Z"),
                        Instant.parse("2026-10-03T08:00:01Z"),
                        Instant.parse("2026-10-03T08:00:02Z")
                )), 100, 0, false));

        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(123))
                .andExpect(jsonPath("$.items[0].roomId").value("living-room"))
                .andExpect(jsonPath("$.items[0].sensorId").value("air-1"))
                .andExpect(jsonPath("$.items[0].deviceId").doesNotExist())
                .andExpect(jsonPath("$.items[0].sensorType").value("AIR"))
                .andExpect(jsonPath("$.items[0].temperature").value(22.5))
                .andExpect(jsonPath("$.items[0].light").value(nullValue()))
                .andExpect(jsonPath("$.items[0].lightLevel").value(nullValue()))
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void occupancyHistoryPreservesBooleanFalseAndNull() throws Exception {
        when(historyRepository.findOccupancyEvents(
                eq("living-room"), isNull(), isNull(), isNull(), eq(100), eq(0)))
                .thenReturn(new PageResponse<>(List.of(new OccupancyEvent(
                        456L,
                        "living-room",
                        "presence-1",
                        EventType.PRESENCE,
                        false,
                        null,
                        Instant.parse("2026-10-03T08:00:00Z"),
                        Instant.parse("2026-10-03T08:00:01Z"),
                        Instant.parse("2026-10-03T08:00:02Z")
                )), 100, 0, false));

        mockMvc.perform(get("/api/v1/rooms/living-room/occupancy-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].roomId").value("living-room"))
                .andExpect(jsonPath("$.items[0].sensorId").value("presence-1"))
                .andExpect(jsonPath("$.items[0].deviceId").doesNotExist())
                .andExpect(jsonPath("$.items[0].present").value(false))
                .andExpect(jsonPath("$.items[0].open").value(nullValue()))
                .andExpect(jsonPath("$.items[0].eventType").value("PRESENCE"));
    }

    @Test
    void customLimitAndOffsetAreForwarded() throws Exception {
        when(historyRepository.findEnvironmentReadings(
                eq("living-room"), isNull(), isNull(), isNull(), isNull(), eq(2), eq(5)))
                .thenReturn(new PageResponse<>(List.of(), 2, 5, false));

        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .param("limit", "2")
                        .param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(5));

        verify(historyRepository).findEnvironmentReadings(
                eq("living-room"), isNull(), isNull(), isNull(), isNull(), eq(2), eq(5));
    }

    @Test
    void optionalFiltersAreForwarded() throws Exception {
        Instant from = Instant.parse("2026-10-03T00:00:00Z");
        Instant to = Instant.parse("2026-10-03T23:59:59Z");
        when(historyRepository.findEnvironmentReadings(
                eq("living-room"), eq(SensorType.AIR), eq("air-10"), eq(from), eq(to), eq(100), eq(0)))
                .thenReturn(new PageResponse<>(List.of(), 100, 0, false));
        when(historyRepository.findOccupancyEvents(
                eq("living-room"), eq(EventType.OPENING), eq(from), eq(to), eq(100), eq(0)))
                .thenReturn(new PageResponse<>(List.of(), 100, 0, false));

        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .param("sensorType", "AIR")
                        .param("sensorId", "air-10")
                        .param("from", "2026-10-03T00:00:00Z")
                        .param("to", "2026-10-03T23:59:59Z"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/rooms/living-room/occupancy-events")
                        .param("eventType", "OPENING")
                        .param("from", "2026-10-03T00:00:00Z")
                        .param("to", "2026-10-03T23:59:59Z"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidEnumReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .param("sensorType", "WATER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void invalidTimestampReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .param("from", "not-a-timestamp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void invalidLimitReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .param("limit", "501"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeOffsetReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/living-room/occupancy-events")
                        .param("offset", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fromAfterToReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .param("from", "2026-10-04T00:00:00Z")
                        .param("to", "2026-10-03T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void emptyHistoryReturns200() throws Exception {
        when(historyRepository.findEnvironmentReadings(
                any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 100, 0, false));

        mockMvc.perform(get("/api/v1/rooms/unknown/environment-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void repositoryFailureReturns500() throws Exception {
        when(historyRepository.findEnvironmentReadings(
                any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("database_error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void corsAllowsConfiguredOrigin() throws Exception {
        when(historyRepository.findEnvironmentReadings(
                any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(), 100, 0, false));

        mockMvc.perform(options("/api/v1/rooms/living-room/environment-readings")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsRejectsUnrelatedOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/rooms/living-room/environment-readings")
                        .header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
