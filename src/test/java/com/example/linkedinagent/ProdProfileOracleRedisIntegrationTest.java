package com.example.linkedinagent;

import com.example.linkedinagent.client.LinkedInPublishingClient;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.PublicationResult;
import com.example.linkedinagent.repository.AppUserRepository;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.security.TokenCipher;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Executa o perfil {@code prod} real contra Oracle e Redis descartáveis (Testcontainers):
 * aplica as migrations Flyway Oracle, valida o schema com Hibernate ({@code ddl-auto=validate}),
 * percorre o fluxo cadastro → login → draft → aprovação → publicação idempotente e confirma que
 * o rate limiting usa o Redis. É ignorado automaticamente quando não há Docker disponível.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker = true)
class ProdProfileOracleRedisIntegrationTest {

    private static final String PASSWORD = "valid-test-password";
    // Chave fictícia gerada em tempo de execução (32 bytes distintos), para passar na validação de
    // chaves fracas do perfil prod sem versionar nenhum valor parecido com segredo.
    private static final String TEST_KEY_BASE64 = testKeyBase64();

    private static String testKeyBase64() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (index * 7 + 3);
        }
        return java.util.Base64.getEncoder().encodeToString(key);
    }

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void prodProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", ORACLE::getJdbcUrl);
        registry.add("DATABASE_USERNAME", ORACLE::getUsername);
        registry.add("DATABASE_PASSWORD", ORACLE::getPassword);
        registry.add("RATE_LIMIT_REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("RATE_LIMIT_HMAC_KEY", () -> TEST_KEY_BASE64);
        registry.add("TOKEN_ENCRYPTION_KEY", () -> TEST_KEY_BASE64);
        registry.add("LINKEDIN_CLIENT_ID", () -> "test-client-id");
        registry.add("LINKEDIN_CLIENT_SECRET", () -> "test-client-secret");
        registry.add("LINKEDIN_REDIRECT_URI", () -> "https://example.test/api/v1/linkedin/oauth/callback");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "https://frontend.example.test");
        registry.add("APP_BASE_URL", () -> "https://example.test");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private LinkedInAuthorizationRepository authorizationRepository;

    @Autowired
    private TokenCipher tokenCipher;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private LinkedInPublishingClient publishingClient;

    @Test
    void oracleMigrationsAndHibernateValidationSupportTheFullPublicationFlowWithRedisRateLimiting() throws Exception {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isGreaterThanOrEqualTo(2);

        String email = UUID.randomUUID() + "@example.com";
        MockHttpSession session = registerAndLogin(email);
        UUID userId = appUserRepository.findByEmail(email).orElseThrow().getId();

        Instant now = Instant.now();
        LinkedInAuthorization authorization = LinkedInAuthorization.createForUser(AppUser.reference(userId), now);
        authorization.refreshTokens(
                "member-123",
                tokenCipher.encrypt("access-token", userId.toString()),
                null,
                "openid profile w_member_social",
                now.plusSeconds(3600),
                now);
        authorizationRepository.saveAndFlush(authorization);
        when(publishingClient.publish(any(), any())).thenReturn(new PublicationResult("urn:li:share:oracle-1"));

        UUID draftId = createDraft(session);
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/drafts/{id}/publish", draftId)
                            .session(session).with(csrf())
                            .header("Idempotency-Key", "test-idempotency-key-oracle-1"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PUBLISHED"))
                    .andExpect(jsonPath("$.externalPostId").value("urn:li:share:oracle-1"));
        }
        verify(publishingClient, times(1)).publish(any(), any());

        mockMvc.perform(get("/api/v1/publications").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PUBLISHED"));

        assertThat(redisTemplate.keys("linkedin-integration:*")).isNotEmpty();
    }

    @Test
    void oracleEnforcesCaseInsensitiveUniqueEmail() {
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO app_user (id, email, display_name, password_hash, status, created_at, updated_at)
                VALUES (?, ?, 'Oracle User', 'hash', 'ACTIVE', ?, ?)
                """, UUID.randomUUID().toString(), "Case.User@Example.com", now, now);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO app_user (id, email, display_name, password_hash, status, created_at, updated_at)
                VALUES (?, ?, 'Duplicate', 'hash', 'ACTIVE', ?, ?)
                """, UUID.randomUUID().toString(), "case.user@example.com", now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private MockHttpSession registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Oracle User","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private UUID createDraft(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/drafts")
                        .session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hello from Oracle","mediaCategory":"NONE"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }
}
