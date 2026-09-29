package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.adapter.out.persistence.entity.AppUserEntity;
import com.example.linkedinagent.adapter.out.persistence.entity.LinkedInAuthorizationEntity;
import com.example.linkedinagent.adapter.out.persistence.repository.AppUserRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.adapter.out.persistence.repository.OAuthTransactionRepository;
import com.example.linkedinagent.application.ports.out.LinkedInOAuthClient;
import com.example.linkedinagent.application.ports.out.OAuthTransactionPort;
import com.example.linkedinagent.application.ratelimit.RateLimitStore;
import com.jayway.jsonpath.JsonPath;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    private static final String PASSWORD = "valid-test-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkedInAuthorizationRepository linkedInAuthorizationRepository;

    @Autowired
    private OAuthTransactionRepository oauthTransactionRepository;

    @Autowired
    private OAuthTransactionPort oauthTransactionPort;

    @MockitoBean
    private LinkedInOAuthClient linkedInOAuthClient;

    @MockitoBean
    private RateLimitStore rateLimitStore;

    @BeforeEach
    void allowRequestsInExistingAuthenticationScenarios() {
        when(rateLimitStore.acquire(anyList())).thenReturn(Optional.empty());
    }

    @Test
    void registrationCreatesIndependentAccountAndStoresOnlyPasswordHash() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"  Test User  ","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(normalizedEmail))
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        AppUserEntity savedUser = appUserRepository.findByEmail(normalizedEmail).orElseThrow();
        assertThat(savedUser.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, savedUser.getPasswordHash())).isTrue();
    }

    @Test
    void registrationRejectsEmailAlreadyInUseRegardlessOfCase() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Another User","password":"%s"}
                                """.formatted(email.toUpperCase(Locale.ROOT), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void loginCreatesSessionAndCurrentUserRequiresThatSession() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);
        AppUserEntity user = appUserRepository.findByEmail(email).orElseThrow();
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.id").isNotEmpty());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stateChangingRequestsRequireCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Test User","password":"%s"}
                                """.formatted(UUID.randomUUID() + "@example.com", PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutInvalidatesTheAuthenticatedSession() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsGenericErrorForInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"missing@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect."));
    }

    @Test
    void loginRejectsWrongPasswordForExistingAccountWithSameGenericError() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"incorrect-password"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect."));
    }

    @Test
    void loginNormalizesEmailBeforeAuthenticating() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email.toUpperCase(Locale.ROOT), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void authenticatedSessionsRemainIsolatedBetweenUsers() throws Exception {
        String firstEmail = UUID.randomUUID() + "@example.com";
        String secondEmail = UUID.randomUUID() + "@example.com";
        register(firstEmail);
        register(secondEmail);

        MockHttpSession firstSession = login(firstEmail);
        MockHttpSession secondSession = login(secondEmail);

        assertThat(firstSession.getId()).isNotEqualTo(secondSession.getId());
        mockMvc.perform(get("/api/v1/users/me").session(firstSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(firstEmail));
        mockMvc.perform(get("/api/v1/users/me").session(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(secondEmail));
    }

    @Test
    void csrfEndpointReturnsUsableTokenForStateChangingRequest() throws Exception {
        MvcResult csrfResponse = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").isNotEmpty())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        String csrfBody = csrfResponse.getResponse().getContentAsString();
        String headerName = JsonPath.read(csrfBody, "$.headerName");
        String token = JsonPath.read(csrfBody, "$.token");
        MockHttpSession session = (MockHttpSession) csrfResponse.getRequest().getSession(false);

        mockMvc.perform(post("/api/v1/auth/register")
                        .session(session)
                        .header(headerName, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Test User","password":"%s"}
                                """.formatted(UUID.randomUUID() + "@example.com", PASSWORD)))
                .andExpect(status().isCreated());
    }

    @Test
    void registrationRejectsInvalidEmailAndTooShortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","displayName":"Test User","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Test User","password":"short"}
                                """.formatted(UUID.randomUUID() + "@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request validation failed."));
    }

    @Test
    void registrationRejectsPasswordExceedingBcryptUtf8ByteLimit() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Test User","password":"%s"}
                                """.formatted(UUID.randomUUID() + "@example.com", "é".repeat(37))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
    }

    @Test
    void oauthEndpointsRequireLocalAuthenticationExceptStateProtectedCallback() throws Exception {
        mockMvc.perform(get("/api/v1/linkedin/oauth/start"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/linkedin/connection"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", "unknown-state")
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));
    }

    @Test
    void oauthCallbackPersistsEncryptedAuthorizationForInitiatingUserAndRejectsReplay() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);
        MockHttpSession session = login(email);
        when(linkedInOAuthClient.authorizationUri(anyString(), anyString()))
                .thenAnswer(invocation -> URI.create(
                        "https://www.linkedin.com/oauth/v2/authorization?state=" + invocation.getArgument(0)));

        MvcResult start = mockMvc.perform(get("/api/v1/linkedin/oauth/start").session(session))
                .andExpect(status().isFound())
                .andReturn();
        String location = start.getResponse().getHeader("Location");
        String state = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("state");
        AppUserEntity user = appUserRepository.findByEmail(email).orElseThrow();
        String stateHash = hashState(state);
        var transaction = oauthTransactionRepository.findByStateHash(stateHash).orElseThrow();
        assertThat(transaction.getAppUser().getId()).isEqualTo(user.getId());
        assertThat(transaction.getStateHash()).isNotEqualTo(state);

        when(linkedInOAuthClient.exchangeAuthorizationCode("authorization-code"))
                .thenReturn(new LinkedInOAuthClient.AccessToken(
                        "raw-access-token",
                        3600,
                        Optional.empty(),
                        Optional.of("openid profile w_member_social")));
        when(linkedInOAuthClient.userInfo("raw-access-token"))
                .thenReturn(new LinkedInOAuthClient.LinkedInMember("linkedin-subject"));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.memberSubject").value("linkedin-subject"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.state").doesNotExist());

        LinkedInAuthorizationEntity authorization =
                linkedInAuthorizationRepository.findByAppUser_Id(user.getId()).orElseThrow();
        assertThat(authorization.getEncryptedAccessToken()).startsWith("v1.")
                .isNotEqualTo("raw-access-token");
        assertThat(authorization.getMemberSubject()).isEqualTo("linkedin-subject");
        assertThat(authorization.getExpiresAt()).isNotNull();

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest());
        verify(linkedInOAuthClient).exchangeAuthorizationCode("authorization-code");
    }

    @Test
    void declinedOauthRequestConsumesStateWithoutCallingProvider() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);
        MockHttpSession session = login(email);
        when(linkedInOAuthClient.authorizationUri(anyString(), anyString()))
                .thenAnswer(invocation -> URI.create(
                        "https://www.linkedin.com/oauth/v2/authorization?state=" + invocation.getArgument(0)));

        MvcResult start = mockMvc.perform(get("/api/v1/linkedin/oauth/start").session(session))
                .andExpect(status().isFound())
                .andReturn();
        String state = UriComponentsBuilder.fromUriString(start.getResponse().getHeader("Location"))
                .build()
                .getQueryParams()
                .getFirst("state");

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", state)
                        .param("error", "user_cancelled_authorize")
                        .param("error_description", "provider-specific private description"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("LinkedIn authorization was not completed."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private description"))));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oauthStateCanOnlyBeConsumedOnceAcrossConcurrentCallbacks() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);
        UUID userId = appUserRepository.findByEmail(email).orElseThrow().getId();
        String stateHash = UUID.randomUUID().toString();
        Instant now = Instant.now();
        oauthTransactionPort.create(userId, stateHash, "openid profile", now, now.plusSeconds(600));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return oauthTransactionPort.consume(stateHash, Instant.now()).isPresent();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return oauthTransactionPort.consume(stateHash, Instant.now()).isPresent();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void connectionCanBeReadAndDisconnectedOnlyByItsOwner() throws Exception {
        String firstEmail = UUID.randomUUID() + "@example.com";
        String secondEmail = UUID.randomUUID() + "@example.com";
        register(firstEmail);
        register(secondEmail);
        MockHttpSession firstSession = login(firstEmail);
        MockHttpSession secondSession = login(secondEmail);
        when(linkedInOAuthClient.authorizationUri(anyString(), anyString()))
                .thenAnswer(invocation -> URI.create(
                        "https://www.linkedin.com/oauth/v2/authorization?state=" + invocation.getArgument(0)));

        MvcResult start = mockMvc.perform(get("/api/v1/linkedin/oauth/start").session(firstSession))
                .andExpect(status().isFound())
                .andReturn();
        String state = UriComponentsBuilder.fromUriString(start.getResponse().getHeader("Location"))
                .build()
                .getQueryParams()
                .getFirst("state");
        when(linkedInOAuthClient.exchangeAuthorizationCode("authorization-code"))
                .thenReturn(new LinkedInOAuthClient.AccessToken(
                        "raw-access-token", 3600, Optional.empty(), Optional.empty()));
        when(linkedInOAuthClient.userInfo("raw-access-token"))
                .thenReturn(new LinkedInOAuthClient.LinkedInMember("first-member"));
        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/linkedin/connection").session(firstSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.memberSubject").value("first-member"));
        mockMvc.perform(get("/api/v1/linkedin/connection").session(secondSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));

        MvcResult pendingStart = mockMvc.perform(get("/api/v1/linkedin/oauth/start").session(firstSession))
                .andExpect(status().isFound())
                .andReturn();
        String pendingState = UriComponentsBuilder.fromUriString(pendingStart.getResponse().getHeader("Location"))
                .build()
                .getQueryParams()
                .getFirst("state");

        mockMvc.perform(post("/api/v1/auth/logout").session(secondSession).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/linkedin/connection").session(firstSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/linkedin/connection").session(firstSession).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", pendingState)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/linkedin/connection").session(firstSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void sqliteConnectionsEnforceForeignKeys() {
        assertThat(jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
    }

    private MockHttpSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String hashState(String state) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Test User","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
    }
}
