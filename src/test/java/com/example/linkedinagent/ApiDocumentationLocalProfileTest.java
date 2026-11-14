package com.example.linkedinagent;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        // Logout is handled by Spring Security's LogoutFilter rather than a @RequestMapping method.
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
                }
            }
        }

        assertThat(documentedOperations)
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(runtimeOperations)
                .doesNotContain("GET /api/v1/drafts/{id}");
        assertThat(documentedPaths.keySet()).noneMatch(path -> path.startsWith("/api/v1/opportunities"));
    }

    @Test
    void openApiSchemasExcludeSensitiveCredentialsAndEnforceBoundaryConstraints() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.password").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ConnectionResponse.properties.accessToken").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ConnectionResponse.properties.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ConnectionResponse.properties.encryptedAccessToken").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.minLength").value(12))
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.maxLength").value(72))
                .andExpect(jsonPath("$.components.schemas.PublicationResponse.properties.status.enum",
                        containsInAnyOrder("PUBLISHING", "PUBLISHED", "FAILED", "OUTCOME_UNKNOWN")))
                .andExpect(jsonPath("$.components.schemas.PublicationResponse.properties.draftId.type", hasItem("null")));
    }
}
