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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;

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
                .andExpect(jsonPath("$.paths['/api/v1/publications'].get.description",
                        containsString("somente publicações do usuário autenticado")))
                .andExpect(jsonPath("$.paths['/api/v1/drafts/{id}'].patch.description",
                        containsString("Só funciona enquanto o draft estiver em DRAFT")))
                .andExpect(jsonPath("$.paths['/api/v1/drafts/{id}/publish'].post.responses['409'].description",
                        containsString("reconciliação")))
                .andExpect(jsonPath("$.paths['/api/v1/drafts/{id}/publish'].post.responses['502'].description",
                        containsString("desconhecido")))
                .andExpect(jsonPath("$.components.schemas.CreateDraftRequest.properties.mediaCategory.example",
                        org.hamcrest.Matchers.is("NONE")))
                .andExpect(jsonPath("$.paths['/api/v1/drafts'].post.requestBody.content['application/json'].examples['Draft textual válido'].value.mediaCategory",
                        org.hamcrest.Matchers.is("NONE")));
    }

    @Test
    void openApiDocumentsThePublicationIdempotencyAndFailureContract() throws Exception {
        String publish = "$.paths['/api/v1/drafts/{id}/publish'].post";
        String history = "$.paths['/api/v1/publications'].get";
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                // Header obrigatório no contrato, com os mesmos limites validados pelo serviço.
                .andExpect(jsonPath(publish + ".parameters[?(@.name == 'Idempotency-Key')].in").value(hasItem("header")))
                .andExpect(jsonPath(publish + ".parameters[?(@.name == 'Idempotency-Key')].required").value(hasItem(true)))
                .andExpect(jsonPath(publish + ".parameters[?(@.name == 'Idempotency-Key')].schema.maxLength").value(hasItem(200)))
                // Replay concluído mantém 201 e o mesmo schema de publicação.
                .andExpect(jsonPath(publish + ".description", containsString("mesmo status 201")))
                .andExpect(jsonPath(publish + ".responses['201'].content['application/json'].schema['$ref']",
                        endsWith("/PublicationResponse")))
                // Cada código de erro emitido pelo fluxo de publicação tem schema e exemplo.
                .andExpect(jsonPath(publish + ".responses['400'].content['application/json'].examples.IDEMPOTENCY_KEY_REQUIRED").exists())
                .andExpect(jsonPath(publish + ".responses['404'].content['application/json'].examples.DRAFT_NOT_FOUND").exists())
                .andExpect(jsonPath(publish + ".responses['409'].content['application/json'].schema['$ref']", endsWith("/ApiError")))
                .andExpect(jsonPath(publish + ".responses['409'].content['application/json'].examples.PUBLICATION_ALREADY_PROCESSED.value.code")
                        .value("PUBLICATION_ALREADY_PROCESSED"))
                .andExpect(jsonPath(publish + ".responses['409'].content['application/json'].examples.PUBLICATION_OUTCOME_UNKNOWN.value.code")
                        .value("PUBLICATION_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath(publish + ".responses['422'].content['application/json'].examples.DRAFT_NOT_APPROVED").exists())
                .andExpect(jsonPath(publish + ".responses['422'].content['application/json'].examples.LINKEDIN_CONNECTION_REQUIRED").exists())
                .andExpect(jsonPath(publish + ".responses['429'].content['application/json'].examples.RATE_LIMITED").exists())
                .andExpect(jsonPath(publish + ".responses['429'].content['application/json'].examples.LINKEDIN_RATE_LIMITED").exists())
                .andExpect(jsonPath(publish + ".responses['502'].content['application/json'].examples.LINKEDIN_RESULT_UNKNOWN.value.code")
                        .value("LINKEDIN_RESULT_UNKNOWN"))
                .andExpect(jsonPath(publish + ".responses['503'].content['application/json'].examples.RATE_LIMIT_UNAVAILABLE").exists())
                .andExpect(jsonPath(publish + ".responses['401']").exists())
                .andExpect(jsonPath(publish + ".responses['403']").exists())
                // Histórico expõe todos os estados persistidos, inclusive o resultado ambíguo.
                .andExpect(jsonPath(history + ".description", containsString("OUTCOME_UNKNOWN")))
                .andExpect(jsonPath(history + ".responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/PublicationPageResponse")))
                .andExpect(jsonPath(history + ".responses['200'].content['application/json'].examples.*.value.content[*].status")
                        .value(hasItem("OUTCOME_UNKNOWN")))
                .andExpect(jsonPath(history + ".responses['400'].content['application/json'].examples.INVALID_PAGINATION").exists())
                .andExpect(jsonPath("$.components.schemas.PublicationResponse.properties.status.enum",
                        containsInAnyOrder("PUBLISHING", "PUBLISHED", "FAILED", "OUTCOME_UNKNOWN")))
                .andExpect(jsonPath("$.components.schemas.PublicationResponse.properties.draftId.type", hasItem("null")))
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.code").exists());
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
