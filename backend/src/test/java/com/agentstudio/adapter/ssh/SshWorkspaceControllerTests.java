package com.agentstudio.adapter.ssh;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SshWorkspaceControllerTests {
    @Autowired MockMvc mvc;

    @Test
    void savesAndReadsNonSecretSshWorkspaceConfiguration() throws Exception {
        mvc.perform(put("/api/ssh/workspace").contentType(MediaType.APPLICATION_JSON).content("""
                {"host":"ssh.example.test","port":2222,"username":"deploy",
                 "remoteRoot":"/srv/example","hostKeySha256":"SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                 "passwordSecret":"TEST_SSH_PASSWORD"}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.passwordConfigured").value(false))
                .andExpect(jsonPath("$.status").value("NOT_TESTED"));

        mvc.perform(get("/api/ssh/workspace")).andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("ssh.example.test"))
                .andExpect(jsonPath("$.port").value(2222))
                .andExpect(jsonPath("$.username").value("deploy"))
                .andExpect(jsonPath("$.remoteRoot").value("/srv/example"))
                .andExpect(jsonPath("$.passwordSecret").value("TEST_SSH_PASSWORD"));
    }

    @Test
    void rejectsRemoteFilesystemRoot() throws Exception {
        mvc.perform(put("/api/ssh/workspace").contentType(MediaType.APPLICATION_JSON).content("""
                {"host":"ssh.example.test","port":22,"username":"root","remoteRoot":"/",
                 "hostKeySha256":"SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                 "passwordSecret":"TEST_SSH_PASSWORD"}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("非根目录")));
    }
}
