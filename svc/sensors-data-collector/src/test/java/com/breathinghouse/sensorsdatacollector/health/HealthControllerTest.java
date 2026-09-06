package com.breathinghouse.sensorsdatacollector.health;

import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    private Mqtt5AsyncClient hiveMqClient;

    @Test
    void getHealthWhenMqttIsConnected() throws Exception {
        when(hiveMqClient.getState()).thenReturn(MqttClientState.CONNECTED);

        mockMvc.perform(get("/live"))
                .andExpect(status().isOk())
                .andExpect(content().string("LIVE\n"));

        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string("READY\n"));
    }

    @Test
    void getHealthWhenMqttIsDisconnected() throws Exception {
        when(hiveMqClient.getState()).thenReturn(MqttClientState.DISCONNECTED);

        mockMvc.perform(get("/live"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("NOT_READY: MQTT consumer state is DISCONNECTED\n"));
    }
}
