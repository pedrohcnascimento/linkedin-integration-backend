package com.example.linkedinagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ApiDocumentationLocalProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocumentationIsAvailableWithoutAuthenticationInLocalProfile() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void openApiDocumentsTheRealBrowserBasedLinkedInFlow() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.description", containsString("Como testar a conexão com o LinkedIn")))
                .andExpect(jsonPath("$.paths['/api/v1/linkedin/oauth/start'].get.description",
                        containsString("Location")))
                .andExpect(jsonPath("$.paths['/api/v1/linkedin/oauth/callback'].get.description",
                        containsString("Não invente nem edite")))
                .andExpect(jsonPath("$.paths['/api/v1/linkedin/connection'].get.description",
                        containsString("connected: true")));
    }

    @Test
    void healthEndpointReportsTheRunningApplication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
