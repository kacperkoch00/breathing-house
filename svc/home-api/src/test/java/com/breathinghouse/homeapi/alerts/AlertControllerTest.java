package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertConfiguration.Severity;
import com.breathinghouse.homeapi.config.CorsConfig;
import com.breathinghouse.homeapi.history.HistoryExceptionHandler;
import com.breathinghouse.homeapi.history.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AlertController.class)
@Import({HistoryExceptionHandler.class, CorsConfig.class})
class AlertControllerTest {

    private static final Instant TRIGGERED = Instant.parse("2026-10-03T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertRepository alertRepository;

    @Test
    void listReturnsPageWithNullableSensorIdAndNoRuleSnapshot() throws Exception {
        when(alertRepository.findAlerts(
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(100), eq(0)))
                .thenReturn(new PageResponse<>(List.of(
                        alert(2, "poor-air-and-hot", "living-room", null, Severity.CRITICAL, AlertStatus.ACTIVE, null),
                        alert(1, "high-co2", "bedroom", "air-1", Severity.WARNING, AlertStatus.RESOLVED,
                                Instant.parse("2026-10-03T09:00:00Z"))), 100, 0, false));

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(2))
                .andExpect(jsonPath("$.items[0].ruleId").value("poor-air-and-hot"))
                .andExpect(jsonPath("$.items[0].roomId").value("living-room"))
                .andExpect(jsonPath("$.items[0].sensorId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].triggerValue").value("1600"))
                .andExpect(jsonPath("$.items[0].triggeredAt").value("2026-10-03T08:00:00Z"))
                .andExpect(jsonPath("$.items[0].resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].lastEvaluatedAt").value("2026-10-03T08:05:00Z"))
                .andExpect(jsonPath("$.items[0].ruleSnapshot").doesNotExist())
                .andExpect(jsonPath("$.items[0].deviceId").doesNotExist())
                .andExpect(jsonPath("$.items[1].sensorId").value("air-1"))
                .andExpect(jsonPath("$.items[1].status").value("RESOLVED"))
                .andExpect(jsonPath("$.items[1].resolvedAt").value("2026-10-03T09:00:00Z"))
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void filtersLimitAndOffsetAreForwarded() throws Exception {
        Instant from = Instant.parse("2026-10-03T00:00:00Z");
        Instant to = Instant.parse("2026-10-03T23:59:59Z");
        when(alertRepository.findAlerts(
                eq(AlertStatus.ACTIVE), eq("living-room"), eq("air-1"), eq(Severity.WARNING),
                eq(from), eq(to), eq(2), eq(4)))
                .thenReturn(new PageResponse<>(List.of(), 2, 4, true));

        mockMvc.perform(get("/api/v1/alerts")
                        .param("status", "ACTIVE")
                        .param("roomId", "living-room")
                        .param("sensorId", "air-1")
                        .param("severity", "WARNING")
                        .param("from", "2026-10-03T00:00:00Z")
                        .param("to", "2026-10-03T23:59:59Z")
                        .param("limit", "2")
                        .param("offset", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.offset").value(4))
                .andExpect(jsonPath("$.hasMore").value(true));

        verify(alertRepository).findAlerts(
                AlertStatus.ACTIVE, "living-room", "air-1", Severity.WARNING, from, to, 2, 4);
    }

    @Test
    void fromAfterToIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")
                        .param("from", "2026-10-04T00:00:00Z")
                        .param("to", "2026-10-03T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));

        verifyNoInteractions(alertRepository);
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        for (String[] param : new String[][]{
                {"status", "UNKNOWN"},
                {"severity", "LOW"},
                {"from", "yesterday"},
                {"limit", "0"},
                {"limit", "501"},
                {"offset", "-1"}}) {
            mockMvc.perform(get("/api/v1/alerts").param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }
        verifyNoInteractions(alertRepository);
    }

    @Test
    void detailIncludesRuleSnapshotAsJsonObject() throws Exception {
        when(alertRepository.findAlertById(7L)).thenReturn(Optional.of(new AlertDetail(
                7L, "high-co2", "bedroom", "air-1", Severity.WARNING, AlertStatus.ACTIVE, "CO2 high", "1600",
                TRIGGERED, null, TRIGGERED.plusSeconds(300),
                "{\"id\":\"high-co2\",\"threshold\":1500}")));

        mockMvc.perform(get("/api/v1/alerts/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.sensorId").value("air-1"))
                .andExpect(jsonPath("$.ruleSnapshot.id").value("high-co2"))
                .andExpect(jsonPath("$.ruleSnapshot.threshold").value(1500));
    }

    @Test
    void missingAlertReturnsNotFound() throws Exception {
        when(alertRepository.findAlertById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/alerts/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void nonNumericIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    private static Alert alert(
            long id,
            String ruleId,
            String roomId,
            String sensorId,
            Severity severity,
            AlertStatus status,
            Instant resolvedAt) {
        return new Alert(id, ruleId, roomId, sensorId, severity, status, "message", "1600",
                id == 2 ? TRIGGERED : TRIGGERED.minusSeconds(3600), resolvedAt,
                id == 2 ? TRIGGERED.plusSeconds(300) : TRIGGERED);
    }
}
