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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class SshWorkspaceControllerTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean SshWorkspaceBrowserService browser;

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

    @Test
    void exposesExplicitReadOnlyBrowserEndpointsWithoutAgentRun() throws Exception {
        when(browser.directory("src", 50)).thenReturn(objectMapper.readTree("""
                {"target":"tester@example:22/workspace","path":"src","entries":[
                  {"name":"App.java","path":"src/App.java","type":"FILE","sizeBytes":12}
                ],"truncated":false}
                """));
        when(browser.file("src/App.java", 1, 200)).thenReturn(objectMapper.readTree("""
                {"path":"src/App.java","sha256":"abc","sizeBytes":12,"startLine":1,
                 "endLine":1,"totalLines":1,"content":"class App {}","truncated":false}
                """));

        mvc.perform(get("/api/ssh/workspace/browser/directory")
                        .param("path", "src").param("maxEntries", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].path").value("src/App.java"));
        mvc.perform(get("/api/ssh/workspace/browser/file")
                        .param("path", "src/App.java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("class App {}"));
    }

    @Test
    void savesIndependentDeploymentProfileAndRejectsUnsafeHealthTarget() throws Exception {
        mvc.perform(put("/api/ssh/deployment").contentType(MediaType.APPLICATION_JSON).content("""
                {"localSourceRoot":"D:/idea_work/site","remoteDeployRoot":"/srv/old-things",
                 "remoteBackupRoot":"/srv/old-things-backups","composeFile":"compose.yml",
                 "composeProject":"old-things","nginxConfig":"nginx.conf",
                 "healthUrl":"http://127.0.0.1/"}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.remoteDeployRoot").value("/srv/old-things"))
                .andExpect(jsonPath("$.status").value("NOT_TESTED"));

        mvc.perform(get("/api/ssh/deployment")).andExpect(status().isOk())
                .andExpect(jsonPath("$.composeProject").value("old-things"));

        mvc.perform(put("/api/ssh/deployment").contentType(MediaType.APPLICATION_JSON).content("""
                {"localSourceRoot":"D:/idea_work/site","remoteDeployRoot":"/srv/old-things",
                 "remoteBackupRoot":"/srv/old-things-backups","composeFile":"compose.yml",
                 "composeProject":"old-things","nginxConfig":"nginx.conf",
                 "healthUrl":"https://attacker.example/"}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("127.0.0.1")));

        mvc.perform(put("/api/ssh/deployment").contentType(MediaType.APPLICATION_JSON).content("""
                {"localSourceRoot":"D:/idea_work/site","remoteDeployRoot":"/srv/old-things;down",
                 "remoteBackupRoot":"/srv/old-things-backups","composeFile":"compose.yml",
                 "composeProject":"old-things","nginxConfig":"nginx.conf",
                 "healthUrl":"http://127.0.0.1/"}
                """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不安全字符")));
    }
}
