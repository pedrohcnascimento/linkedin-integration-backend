package com.example.linkedinagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
                        containsString("connected: true")))
                .andExpect(jsonPath("$.info.description", containsString("Como testar drafts pelo Swagger local")))
                .andExpect(jsonPath("$.paths['/api/v1/drafts'].get.description",
                        containsString("somente drafts pertencentes")))
                .andExpect(jsonPath("$.paths['/api/v1/drafts/{id}'].patch.description",
                        containsString("Só funciona enquanto o draft estiver em DRAFT")))
                .andExpect(jsonPath("$.components.schemas.CreateDraftRequest.properties.mediaCategory.example",
                        org.hamcrest.Matchers.is("NONE")))
                .andExpect(jsonPath("$.paths['/api/v1/drafts'].post.requestBody.content['application/json'].examples['Draft textual válido'].value.mediaCategory",
                        org.hamcrest.Matchers.is("NONE")));
    }

    @Test
    void healthEndpointReportsTheRunningApplication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void localLoginRenewsReadableCsrfCookieForSwagger() throws Exception {
        String email = "swagger-local-" + System.nanoTime() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Swagger User","password":"valid-test-password"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"valid-test-password"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        org.assertj.core.api.Assertions.assertThat(response.getCookie("XSRF-TOKEN")).isNotNull();
        org.assertj.core.api.Assertions.assertThat(response.getCookie("XSRF-TOKEN").isHttpOnly()).isFalse();
    }
}
