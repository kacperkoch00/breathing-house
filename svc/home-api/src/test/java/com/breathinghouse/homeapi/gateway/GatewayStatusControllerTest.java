package com.breathinghouse.homeapi.gateway;

import com.breathinghouse.homeapi.config.CorsConfig;
import com.breathinghouse.homeapi.history.HistoryExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GatewayStatusController.class)
@Import({HistoryExceptionHandler.class, CorsConfig.class})
class GatewayStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GatewayStatusService gatewayStatusService;

    @Test
    void returnsOnlineTrue() throws Exception {
        when(gatewayStatusService.isOnline()).thenReturn(true);

        mockMvc.perform(get("/api/v1/sensor-gateway/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.online").value(true));
    }

    @Test
    void returnsOnlineFalse() throws Exception {
        when(gatewayStatusService.isOnline()).thenReturn(false);

        mockMvc.perform(get("/api/v1/sensor-gateway/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(false));
    }

    @Test
    void returnsStandard500WhenRepositoryFails() throws Exception {
        when(gatewayStatusService.isOnline())
                .thenThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(get("/api/v1/sensor-gateway/status"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("database_error"))
                .andExpect(jsonPath("$.message").value("Database query failed"));
    }
}
