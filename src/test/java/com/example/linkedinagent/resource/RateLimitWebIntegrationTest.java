package com.example.linkedinagent.resource;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.RateLimitBucket;
import com.example.linkedinagent.repository.RateLimitStore;
import com.example.linkedinagent.security.AppUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitWebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RateLimitStore rateLimitStore;

    @BeforeEach
    void denyRateLimitedRequests() {
        when(rateLimitStore.acquire(anyList())).thenReturn(Optional.of(Duration.ofSeconds(37)));
    }

    @Test
    void rateLimitsLoginWithGeneric429AndRetryAfter() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","password":"valid-test-password"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please retry later."));
    }

    @Test
    void rateLimitsRegistrationRequests() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","displayName":"Person","password":"valid-test-password"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please retry later."));
    }

    @Test
    void rateLimitsOAuthCallbackBeforeProcessingProviderParameters() throws Exception {
        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", "test-state")
                        .param("code", "test-code"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please retry later."));
    }

    @Test
    void rateLimitsAuthenticatedOAuthStart() throws Exception {
        mockMvc.perform(get("/api/v1/linkedin/oauth/start")
                        .with(user(testUser())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please retry later."));
    }

    @Test
    void rateLimitsCsrfEndpointByIp() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void rateLimitsFailedLoginAttemptsByAccountAndIpAfterTheIpBudgetAllowsThem() throws Exception {
        when(rateLimitStore.acquire(anyList())).thenAnswer(invocation -> {
            List<RateLimitBucket> buckets = invocation.getArgument(0);
            return buckets.stream().anyMatch(bucket -> bucket.dimension().equals("account-ip"))
                    ? Optional.of(Duration.ofSeconds(19))
                    : Optional.empty();
        });

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","password":"wrong-test-password"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "19"));
    }

    @Test
    void rateLimitsOAuthStartByAccountAndSessionAfterTheIpBudgetAllowsThem() throws Exception {
        when(rateLimitStore.acquire(anyList())).thenAnswer(invocation -> {
            List<RateLimitBucket> buckets = invocation.getArgument(0);
            return buckets.stream().anyMatch(bucket ->
                    bucket.dimension().equals("account") || bucket.dimension().equals("session"))
                    ? Optional.of(Duration.ofSeconds(23))
                    : Optional.empty();
        });

        mockMvc.perform(get("/api/v1/linkedin/oauth/start")
                        .with(user(testUser()))
                        .session(new MockHttpSession()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "23"));
    }

    @Test
    void rateLimitsOAuthCallbackByStateAfterTheIpBudgetAllowsIt() throws Exception {
        when(rateLimitStore.acquire(anyList())).thenAnswer(invocation -> {
            List<RateLimitBucket> buckets = invocation.getArgument(0);
            return buckets.stream().anyMatch(bucket -> bucket.dimension().equals("state"))
                    ? Optional.of(Duration.ofSeconds(29))
                    : Optional.empty();
        });

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", "test-state")
                        .param("code", "test-code"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "29"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please retry later."));
    }

    @Test
    void rateLimitsDraftPublicationRequests() throws Exception {
        mockMvc.perform(post("/api/v1/drafts/{id}/publish", UUID.randomUUID())
                        .with(user(testUser()))
                        .with(csrf())
                        .header("Idempotency-Key", "rate-limit-test-key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please retry later."));
    }

    @Test
    void rateLimitsDraftPublicationByAccountAndSessionAfterIpBudgetAllowsIt() throws Exception {
        when(rateLimitStore.acquire(anyList())).thenAnswer(invocation -> {
            List<RateLimitBucket> buckets = invocation.getArgument(0);
            return buckets.stream().anyMatch(bucket ->
                    bucket.dimension().equals("account") && bucket.scope().equals("publication-write"))
                    ? Optional.of(Duration.ofSeconds(41))
                    : Optional.empty();
        });

        mockMvc.perform(post("/api/v1/drafts/{id}/publish", UUID.randomUUID())
                        .with(user(testUser()))
                        .session(new MockHttpSession())
                        .with(csrf())
                        .header("Idempotency-Key", "rate-limit-account-key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "41"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void failsClosedWhenRateLimitStoreIsUnavailable() throws Exception {
        when(rateLimitStore.acquire(anyList()))
                .thenThrow(new DataAccessResourceFailureException("Redis unavailable"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","password":"valid-test-password"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Requests are temporarily unavailable."));
    }

    @Test
    void rateLimitsDraftWriteRoutesAndIgnoresForgedForwardedHeadersFromUntrustedClients() throws Exception {
        UUID draftId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/drafts")
                        .with(user(testUser()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Draft body","mediaCategory":"NONE"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        mockMvc.perform(patch("/api/v1/drafts/{id}", draftId)
                        .with(user(testUser()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Updated draft body"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        mockMvc.perform(post("/api/v1/drafts/{id}/approve", draftId)
                        .with(user(testUser()))
                        .with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        List<String> capturedIpSubjectHashes = new java.util.ArrayList<>();
        when(rateLimitStore.acquire(anyList())).thenAnswer(invocation -> {
            List<RateLimitBucket> buckets = invocation.getArgument(0);
            buckets.stream()
                    .filter(bucket -> bucket.dimension().equals("ip"))
                    .findFirst()
                    .ifPresent(bucket -> capturedIpSubjectHashes.add(bucket.subjectHash()));
            return Optional.of(Duration.ofSeconds(15));
        });

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .header("X-Forwarded-For", "198.51.100.10")
                        .header("Forwarded", "for=198.51.100.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","password":"valid-test-password"}
                                """))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .header("X-Forwarded-For", "203.0.113.99")
                        .header("Forwarded", "for=203.0.113.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","password":"valid-test-password"}
                                """))
                .andExpect(status().isTooManyRequests());

        assertThat(capturedIpSubjectHashes).hasSize(2);
        assertThat(capturedIpSubjectHashes.get(0)).isEqualTo(capturedIpSubjectHashes.get(1));
    }

    private AppUserPrincipal testUser() {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEmail("oauth-rate-limit@example.test");
        user.setDisplayName("OAuth rate limit test");
        user.setPasswordHash("test-hash");
        user.setStatus(AppUser.STATUS_ACTIVE);
        user.setCreatedAt(Instant.now());
        return new AppUserPrincipal(user);
    }
}
