package com.example.linkedinagent;

import com.example.linkedinagent.repository.RateLimitStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.data.redis.url=redis://localhost:6379",
        "app.linkedin.client-id=prod-profile-test",
        "app.linkedin.client-secret=prod-profile-test",
        "app.linkedin.redirect-uri=https://localhost/api/v1/linkedin/oauth/callback",
        "app.security.token-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.security.cors-allowed-origins=https://localhost",
        "app.rate-limit.hmac-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.base-url=https://localhost"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ApiDocumentationProdProfileTest {

    @MockitoBean(name = "rateLimitStore")
    private RateLimitStore rateLimitStore;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocumentationEndpointsAreNotAvailableInProdProfile() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api-docs").with(user("prod-user")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html").with(user("prod-user")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html").with(user("prod-user")))
                .andExpect(status().isNotFound());
    }
}
