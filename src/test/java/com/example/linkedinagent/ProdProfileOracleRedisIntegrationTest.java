package com.example.linkedinagent;

import com.example.linkedinagent.client.LinkedInPublishingClient;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.Publication;
import com.example.linkedinagent.domain.PublicationResult;
import com.example.linkedinagent.exception.DraftWorkflowException;
import com.example.linkedinagent.repository.AppUserRepository;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.security.TokenCipher;
import com.example.linkedinagent.service.DraftWorkflowService;
import com.jayway.jsonpath.JsonPath;
import org.flywaydb.core.Flyway;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * aplica as migrations Flyway Oracle (V1..V3), valida o schema com Hibernate ({@code ddl-auto=validate}),
 * percorre o fluxo cadastro → login → draft → aprovação → publicação idempotente, valida concorrência
 * multi-thread com lock pessimista e unicidade em Oracle, e confirma que o rate limiting usa o Redis.
 * É ignorado automaticamente quando não há Docker disponível.
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
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private LinkedInAuthorizationRepository authorizationRepository;

    @Autowired
    private TokenCipher tokenCipher;

    @Autowired
    private DraftWorkflowService draftWorkflowService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private LinkedInPublishingClient publishingClient;

    @Test
    void oracleMigrationsAndHibernateValidationSupportTheFullPublicationFlowWithRedisRateLimiting() throws Exception {
        assertThat(flyway.info().applied())
                .hasSizeGreaterThanOrEqualTo(3);

        String email = UUID.randomUUID() + "@example.com";
        MockHttpSession session = registerAndLogin(email);
        UUID userId = appUserRepository.findByEmail(email).orElseThrow().getId();

        authorizeLinkedIn(userId);
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
    void oraclePreventsDuplicateExternalPostsUnderConcurrentSameKeyAndDifferentKeys() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        MockHttpSession session = registerAndLogin(email);
        UUID userId = appUserRepository.findByEmail(email).orElseThrow().getId();
        authorizeLinkedIn(userId);

        AtomicInteger externalCalls = new AtomicInteger();
        when(publishingClient.publish(any(), any())).thenAnswer(invocation -> {
            int callNumber = externalCalls.incrementAndGet();
            Thread.sleep(50);
            return new PublicationResult("urn:li:share:oracle-concurrent-" + callNumber);
        });

        // 1) Concorrência com a MESMA Idempotency-Key sobre o mesmo draft aprovado
        UUID sameKeyDraftId = createDraft(session);
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", sameKeyDraftId).session(session).with(csrf()))
                .andExpect(status().isOk());

        runConcurrentPublishAttempts(userId, sameKeyDraftId, index -> "test-idempotency-key-oracle-same");
        assertThat(externalCalls.get()).isEqualTo(1);

        // 2) Concorrência com CHAVES DIFERENTES sobre o mesmo draft aprovado (lock pessimista Oracle)
        UUID diffKeyDraftId = createDraft(session);
        mockMvc.perform(post("/api/v1/drafts/{id}/approve", diffKeyDraftId).session(session).with(csrf()))
                .andExpect(status().isOk());

        runConcurrentPublishAttempts(userId, diffKeyDraftId, index -> "test-idempotency-key-oracle-diff-" + index);
        assertThat(externalCalls.get()).isEqualTo(2);
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

    private void runConcurrentPublishAttempts(
            UUID userId,
            UUID draftId,
            java.util.function.IntFunction<String> keyFactory) throws Exception {
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Publication>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                String key = keyFactory.apply(index);
                Callable<Publication> task = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrency barrier.");
                    }
                    return draftWorkflowService.publish(userId, draftId, key);
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int publishedResponses = 0;
            int controlledRejections = 0;
            for (Future<Publication> future : futures) {
                try {
                    Publication result = future.get(15, TimeUnit.SECONDS);
                    assertThat(result.getStatus()).isEqualTo(Publication.STATUS_PUBLISHED);
                    publishedResponses++;
                } catch (java.util.concurrent.ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(DraftWorkflowException.class);
                    controlledRejections++;
                }
            }
            assertThat(publishedResponses).isGreaterThanOrEqualTo(1);
            assertThat(publishedResponses + controlledRejections).isEqualTo(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    private void authorizeLinkedIn(UUID userId) {
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
