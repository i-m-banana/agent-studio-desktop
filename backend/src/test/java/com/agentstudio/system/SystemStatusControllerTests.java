package com.agentstudio.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SystemStatusControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsApplicationStatus() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("agent-studio-backend"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesReleaseReadinessWithoutLeakingSecretValues() throws Exception {
        mockMvc.perform(get("/api/system/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.checks").isArray())
                .andExpect(jsonPath("$.checks[?(@.id == 'mysql')]").exists())
                .andExpect(jsonPath("$.checks[?(@.id == 'embedding')]").exists());
    }
}
