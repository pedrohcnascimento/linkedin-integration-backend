package com.example.linkedinagent;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options", "trace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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
    void openApiDocumentsTheAuthenticationSessionAndLogoutContract() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                // CSRF
                .andExpect(jsonPath("$.paths['/api/v1/auth/csrf'].get.responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/CsrfResponse")))
                // Register
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['201'].content['application/json'].schema['$ref']",
                        endsWith("/UserResponse")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['400'].content['application/json'].examples.INVALID_REQUEST").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['400'].content['application/json'].examples.PASSWORD_TOO_LONG").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['409'].content['application/json'].examples.EMAIL_ALREADY_REGISTERED.value.code")
                        .value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['429'].headers['Retry-After']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['503'].content['application/json'].examples.RATE_LIMIT_UNAVAILABLE").exists())
                // Login (UserResponse explícito em 200, sem schema genérico vazio)
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/UserResponse")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['401'].content['application/json'].examples.AUTHENTICATION_FAILED.value.code")
                        .value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['429'].headers['Retry-After']").exists())
                // Logout atendido pelo filtro do Spring Security e documentado via OpenApiCustomizer
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.responses['403']").exists())
                // Sessão atual
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/UserResponse")))
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.responses['401']").exists())
                // Schemas compartilhados e restrições de senha
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.minLength").value(12))
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.maxLength").value(72))
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.password").doesNotExist());
    }

    @Test
    void openApiDocumentsLinkedInOAuthAndDraftLifecycleContracts() throws Exception {
        String oauthStart = "$.paths['/api/v1/linkedin/oauth/start'].get";
        String oauthCallback = "$.paths['/api/v1/linkedin/oauth/callback'].get";
        String getConnection = "$.paths['/api/v1/linkedin/connection'].get";
        String deleteConnection = "$.paths['/api/v1/linkedin/connection'].delete";
        String createDraft = "$.paths['/api/v1/drafts'].post";
        String listDrafts = "$.paths['/api/v1/drafts'].get";
        String editDraft = "$.paths['/api/v1/drafts/{id}'].patch";
        String approveDraft = "$.paths['/api/v1/drafts/{id}/approve'].post";

        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                // LinkedIn OAuth start: 302 + Location header, sem 200 implícito
                .andExpect(jsonPath(oauthStart + ".responses['200']").doesNotExist())
                .andExpect(jsonPath(oauthStart + ".responses['302'].headers['Location']").exists())
                .andExpect(jsonPath(oauthStart + ".responses['401']").exists())
                .andExpect(jsonPath(oauthStart + ".responses['429'].headers['Retry-After']").exists())
                .andExpect(jsonPath(oauthStart + ".responses['500'].content['application/json'].examples.LINKEDIN_CREDENTIAL_STORAGE_ERROR").exists())
                .andExpect(jsonPath(oauthStart + ".responses['503'].content['application/json'].examples.LINKEDIN_OAUTH_NOT_CONFIGURED").exists())
                .andExpect(jsonPath(oauthStart + ".responses['503'].content['application/json'].examples.RATE_LIMIT_UNAVAILABLE").exists())
                // LinkedIn OAuth callback: ConnectionResponse explícito em 200 e códigos sanitizados
                .andExpect(jsonPath(oauthCallback + ".parameters[?(@.name == 'code')]").isNotEmpty())
                .andExpect(jsonPath(oauthCallback + ".parameters[?(@.name == 'state')]").isNotEmpty())
                .andExpect(jsonPath(oauthCallback + ".parameters[?(@.name == 'error')]").isNotEmpty())
                .andExpect(jsonPath(oauthCallback + ".responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/ConnectionResponse")))
                .andExpect(jsonPath(oauthCallback + ".responses['400'].content['application/json'].examples.LINKEDIN_AUTHORIZATION_DENIED").exists())
                .andExpect(jsonPath(oauthCallback + ".responses['400'].content['application/json'].examples.LINKEDIN_AUTHORIZATION_FAILED").exists())
                .andExpect(jsonPath(oauthCallback + ".responses['429'].headers['Retry-After']").exists())
                .andExpect(jsonPath(oauthCallback + ".responses['500'].content['application/json'].examples.LINKEDIN_CREDENTIAL_STORAGE_ERROR").exists())
                .andExpect(jsonPath(oauthCallback + ".responses['502'].content['application/json'].examples.LINKEDIN_PROVIDER_ERROR").exists())
                .andExpect(jsonPath(oauthCallback + ".responses['503'].content['application/json'].examples.LINKEDIN_OAUTH_NOT_CONFIGURED").exists())
                // Conexão LinkedIn (GET + DELETE 204)
                .andExpect(jsonPath(getConnection + ".responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/ConnectionResponse")))
                .andExpect(jsonPath(getConnection + ".responses['200'].content['application/json'].examples.ACTIVE").exists())
                .andExpect(jsonPath(getConnection + ".responses['200'].content['application/json'].examples.DISCONNECTED").exists())
                .andExpect(jsonPath(getConnection + ".responses['401']").exists())
                .andExpect(jsonPath(deleteConnection + ".responses['200']").doesNotExist())
                .andExpect(jsonPath(deleteConnection + ".responses['204']").exists())
                .andExpect(jsonPath(deleteConnection + ".responses['401']").exists())
                .andExpect(jsonPath(deleteConnection + ".responses['403']").exists())
                .andExpect(jsonPath("$.components.schemas.ConnectionResponse.properties.status.enum",
                        containsInAnyOrder("ACTIVE", "EXPIRED", "DISCONNECTED")))
                .andExpect(jsonPath("$.components.schemas.ConnectionResponse.properties.accessToken").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ConnectionResponse.properties.refreshToken").doesNotExist())
                // Criação, listagem paginada, edição e aprovação de drafts
                .andExpect(jsonPath(createDraft + ".summary").isNotEmpty())
                .andExpect(jsonPath(createDraft + ".responses['201'].content['application/json'].schema['$ref']",
                        endsWith("/DraftResponse")))
                .andExpect(jsonPath(createDraft + ".responses['400'].content['application/json'].examples.INVALID_REQUEST").exists())
                .andExpect(jsonPath(createDraft + ".responses['400'].content['application/json'].examples.INVALID_DRAFT").exists())
                .andExpect(jsonPath(createDraft + ".responses['422'].content['application/json'].examples.DRAFT_MEDIA_UNSUPPORTED").exists())
                .andExpect(jsonPath(listDrafts + ".parameters[?(@.name == 'page')].schema.minimum").value(hasItem(0)))
                .andExpect(jsonPath(listDrafts + ".parameters[?(@.name == 'size')].schema.minimum").value(hasItem(1)))
                .andExpect(jsonPath(listDrafts + ".parameters[?(@.name == 'size')].schema.maximum").value(hasItem(50)))
                .andExpect(jsonPath("$.paths['/api/v1/publications'].get.parameters[?(@.name == 'page')].schema.minimum").value(hasItem(0)))
                .andExpect(jsonPath("$.paths['/api/v1/publications'].get.parameters[?(@.name == 'size')].schema.maximum").value(hasItem(50)))
                .andExpect(jsonPath(listDrafts + ".responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/DraftPageResponse")))
                .andExpect(jsonPath(listDrafts + ".responses['400'].content['application/json'].examples.INVALID_PAGINATION").exists())
                .andExpect(jsonPath(listDrafts + ".responses['401']").exists())
                .andExpect(jsonPath(editDraft + ".responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/DraftResponse")))
                .andExpect(jsonPath(editDraft + ".responses['400'].content['application/json'].examples.INVALID_DRAFT_UPDATE").exists())
                .andExpect(jsonPath(editDraft + ".responses['401']").exists())
                .andExpect(jsonPath(editDraft + ".responses['404'].content['application/json'].examples.DRAFT_NOT_FOUND").exists())
                .andExpect(jsonPath(editDraft + ".responses['409'].content['application/json'].examples.DRAFT_INVALID_STATE").exists())
                .andExpect(jsonPath(editDraft + ".responses['422'].content['application/json'].examples.DRAFT_MEDIA_UNSUPPORTED").exists())
                .andExpect(jsonPath(approveDraft + ".responses['200'].content['application/json'].schema['$ref']",
                        endsWith("/DraftResponse")))
                .andExpect(jsonPath(approveDraft + ".responses['401']").exists())
                .andExpect(jsonPath(approveDraft + ".responses['403']").exists())
                .andExpect(jsonPath(approveDraft + ".responses['404'].content['application/json'].examples.DRAFT_NOT_FOUND").exists())
                .andExpect(jsonPath(approveDraft + ".responses['409'].content['application/json'].examples.DRAFT_INVALID_STATE").exists())
                .andExpect(jsonPath("$.components.schemas.DraftResponse.properties.status.enum",
                        containsInAnyOrder("DRAFT", "APPROVED", "PUBLISHED")));
    }

    @Test
    void everyImplementedApiV1RouteIsPresentInOpenApiAndPlannedRoutesAreAbsent() throws Exception {
        Set<String> runtimeOperations = new LinkedHashSet<>();
        for (RequestMappingInfo mappingInfo : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> paths = mappingInfo.getPatternValues();
            Set<RequestMethod> methods = mappingInfo.getMethodsCondition().getMethods();
            for (String path : paths) {
                if (path.startsWith("/api/v1")) {
                    for (RequestMethod method : methods) {
                        runtimeOperations.add(method.name() + " " + path);
                    }
                }
            }
        }
        // Logout é atendido pelo LogoutFilter configurado em SecurityConfig, não por @RequestMapping.
        runtimeOperations.add("POST /api/v1/auth/logout");

        String apiDocsJson = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<String, Map<String, Object>> documentedPaths = JsonPath.read(apiDocsJson, "$.paths");
        Set<String> documentedOperations = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> pathEntry : documentedPaths.entrySet()) {
            String path = pathEntry.getKey();
            if (!path.startsWith("/api/v1")) {
                continue;
            }
            for (Map.Entry<String, Object> methodEntry : pathEntry.getValue().entrySet()) {
                String method = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (HTTP_METHODS.contains(method)) {
                    documentedOperations.add(method.toUpperCase(Locale.ROOT) + " " + path);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> operation = (Map<String, Object>) methodEntry.getValue();
                    assertThat(operation.get("summary"))
                            .as("summary of %s %s", method.toUpperCase(Locale.ROOT), path)
                            .isInstanceOf(String.class)
                            .asString()
                            .isNotBlank();
                }
            }
        }

        assertThat(runtimeOperations).hasSize(15);
        assertThat(documentedOperations).containsExactlyInAnyOrderElementsOf(runtimeOperations);
        assertThat(documentedOperations).doesNotContain("GET /api/v1/drafts/{id}");
        assertThat(documentedPaths.keySet()).noneMatch(path -> path.startsWith("/api/v1/opportunities"));
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
