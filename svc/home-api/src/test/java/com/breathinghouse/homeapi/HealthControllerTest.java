package com.breathinghouse.homeapi;

import com.breathinghouse.homeapi.alerts.AlertRepository;
import com.breathinghouse.homeapi.gateway.GatewayHeartbeatRepository;
import com.breathinghouse.homeapi.history.HistoryRepository;
import com.breathinghouse.homeapi.rooms.RoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HistoryRepository historyRepository;

    @MockitoBean
    private AlertRepository alertRepository;

    @MockitoBean
    private GatewayHeartbeatRepository gatewayHeartbeatRepository;

    @MockitoBean
    private RoomRepository roomRepository;

    @Test
    void getLiveReturnsOkEvenWhenDatabaseUnavailable() throws Exception {
        when(historyRepository.isReady()).thenReturn(false);

        mockMvc.perform(get("/live"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK\n"));
    }

    @Test
    void getReadyReturnsReadyWhenTablesQueryable() throws Exception {
        when(historyRepository.isReady()).thenReturn(true);
        when(alertRepository.isReady()).thenReturn(true);
        when(gatewayHeartbeatRepository.isReady()).thenReturn(true);
        when(roomRepository.isReady()).thenReturn(true);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string("READY\n"));
    }

    @Test
    void getReadyReturns503WhenDatabaseUnavailable() throws Exception {
        when(historyRepository.isReady()).thenReturn(false);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("NOT_READY\n"));
    }

    @Test
    void getReadyReturns503WhenAlertTablesUnavailable() throws Exception {
        when(historyRepository.isReady()).thenReturn(true);
        when(alertRepository.isReady()).thenReturn(false);
        when(gatewayHeartbeatRepository.isReady()).thenReturn(true);
        when(roomRepository.isReady()).thenReturn(true);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("NOT_READY\n"));
    }

    @Test
    void getReadyReturns503WhenGatewayHeartbeatTableUnavailable() throws Exception {
        when(historyRepository.isReady()).thenReturn(true);
        when(alertRepository.isReady()).thenReturn(true);
        when(gatewayHeartbeatRepository.isReady()).thenReturn(false);
        when(roomRepository.isReady()).thenReturn(true);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("NOT_READY\n"));
    }

    @Test
    void getReadyReturns503WhenRoomMetadataUnavailable() throws Exception {
        when(historyRepository.isReady()).thenReturn(true);
        when(alertRepository.isReady()).thenReturn(true);
        when(gatewayHeartbeatRepository.isReady()).thenReturn(true);
        when(roomRepository.isReady()).thenReturn(false);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("NOT_READY\n"));
    }

    @Test
    void getReadyDoesNotDependOnGatewayOnlineState() throws Exception {
        when(historyRepository.isReady()).thenReturn(true);
        when(alertRepository.isReady()).thenReturn(true);
        when(gatewayHeartbeatRepository.isReady()).thenReturn(true);
        when(roomRepository.isReady()).thenReturn(true);

        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string("READY\n"));
    }
}
