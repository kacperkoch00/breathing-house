package com.breathinghouse.alertnotifier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getLiveReturnsOk() throws Exception {
        mockMvc.perform(get("/live"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK\n"));
    }

    @Test
    void getReadyReturnsReady() throws Exception {
        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string("READY\n"));
    }
}
