package com.example.linkedinagent.resource;

import com.example.linkedinagent.client.LinkedInOAuthClient;
import com.example.linkedinagent.domain.RateLimitBucket;
import com.example.linkedinagent.exception.LinkedInProviderException;
import com.example.linkedinagent.repository.RateLimitStore;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthResourceIntegrationTest {

    private static final String PASSWORD = "valid-test-password";

    @Autowired
    private MockMvc mockMvc;

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
        MockHttpSession preLoginSession = new MockHttpSession();
        String preLoginSessionId = preLoginSession.getId();
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .session(preLoginSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotEqualTo(preLoginSessionId);

        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

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
    void csrfEndpointReturnsUsableTokenWithoutAllocatingSessionForUnauthenticatedCallers() throws Exception {
        MvcResult csrfResponse = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            csrfResponse = mockMvc.perform(get("/api/v1/auth/csrf"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.headerName").isNotEmpty())
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andReturn();
            assertThat(csrfResponse.getRequest().getSession(false)).isNull();
        }
        String csrfBody = csrfResponse.getResponse().getContentAsString();
        String headerName = JsonPath.read(csrfBody, "$.headerName");
        String token = JsonPath.read(csrfBody, "$.token");
        Cookie csrfCookie = csrfResponse.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isTrue();

        mockMvc.perform(post("/api/v1/auth/register")
                        .cookie(csrfCookie)
                        .header(headerName, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Test User","password":"%s"}
                                """.formatted(UUID.randomUUID() + "@example.com", PASSWORD)))
                .andExpect(status().isCreated());
    }

    @Test
    void failedLoginsRateLimitOnlyTheOffendingAccountIpAndDoNotLockOutValidCredentials() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);

        java.util.concurrent.atomic.AtomicInteger accountIpFailures = new java.util.concurrent.atomic.AtomicInteger();
        when(rateLimitStore.acquire(anyList())).thenAnswer(invocation -> {
            java.util.List<RateLimitBucket> buckets = invocation.getArgument(0);
            boolean isAccountIpCheck = buckets.stream()
                    .anyMatch(bucket -> bucket.dimension().equals("account-ip"));
            if (isAccountIpCheck && accountIpFailures.incrementAndGet() > 8) {
                return Optional.of( java.time.Duration.ofSeconds(45));
            }
            return Optional.empty();
        });

        for (int attempt = 0; attempt < 8; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("198.51.100.77");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","password":"wrong-password-123"}
                                    """.formatted(email)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-password-123"}
                                """.formatted(email)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.77");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
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
    void oauthEndpointsRequireLocalAuthenticationIncludingStateProtectedCallback() throws Exception {
        mockMvc.perform(get("/api/v1/linkedin/oauth/start"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/linkedin/connection"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .param("state", "unknown-state")
                        .param("code", "authorization-code"))
                .andExpect(status().isUnauthorized());

        String email = UUID.randomUUID() + "@example.com";
        register(email);
        MockHttpSession session = login(email);
        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", "unknown-state")
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));
    }

    @Test
    void oauthCallbackRejectsMissingStateOrAuthorizationCode() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        register(email);
        MockHttpSession session = login(email);

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", "state-without-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));
    }

    @Test
    void corsTrimsConfiguredOriginsAllowsCredentialsAndRejectsUnknownOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers
                        .header().string("Access-Control-Allow-Origin", "http://localhost:5174"))
                .andExpect(MockMvcResultMatchers
                        .header().string("Access-Control-Allow-Credentials", "true"));

        mockMvc.perform(options("/api/v1/auth/csrf")
                        .header("Origin", "http://not-allowed.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(MockMvcResultMatchers
                        .header().doesNotExist("Access-Control-Allow-Origin"));
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
        when(linkedInOAuthClient.exchangeAuthorizationCode("authorization-code"))
                .thenReturn(new LinkedInOAuthClient.AccessToken(
                        "raw-access-token",
                        3600,
                        Optional.empty(),
                        Optional.of("openid profile w_member_social")));
        when(linkedInOAuthClient.userInfo("raw-access-token"))
                .thenReturn(new LinkedInOAuthClient.LinkedInMember("linkedin-subject"));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.memberSubject").value("linkedin-subject"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.state").doesNotExist());

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest());
        verify(linkedInOAuthClient).exchangeAuthorizationCode("authorization-code");
    }

    @Test
    void oauthCallbackRejectsStateFromDifferentAuthenticatedSessionAndConsumesState() throws Exception {
        String victimEmail = UUID.randomUUID() + "@example.com";
        String attackerEmail = UUID.randomUUID() + "@example.com";
        register(victimEmail);
        register(attackerEmail);
        MockHttpSession victimSession = login(victimEmail);
        MockHttpSession attackerSession = login(attackerEmail);
        when(linkedInOAuthClient.authorizationUri(anyString(), anyString()))
                .thenAnswer(invocation -> URI.create(
                        "https://www.linkedin.com/oauth/v2/authorization?state=" + invocation.getArgument(0)));

        MvcResult start = mockMvc.perform(get("/api/v1/linkedin/oauth/start").session(victimSession))
                .andExpect(status().isFound())
                .andReturn();
        String state = UriComponentsBuilder.fromUriString(start.getResponse().getHeader("Location"))
                .build()
                .getQueryParams()
                .getFirst("state");

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(attackerSession)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(victimSession)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));
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
                        .session(session)
                        .param("state", state)
                        .param("error", "user_cancelled_authorize")
                        .param("error_description", "provider-specific private description"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("LinkedIn authorization was not completed."))
                .andExpect(jsonPath("$.message").value(Matchers.not(
                        Matchers.containsString("private description"))));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void providerFailureIsSanitizedAndConsumedStateCannotBeReplayed() throws Exception {
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
        when(linkedInOAuthClient.exchangeAuthorizationCode("authorization-code"))
                .thenThrow(new LinkedInProviderException("provider private details"));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LINKEDIN_PROVIDER_ERROR"))
                .andExpect(jsonPath("$.message").value("LinkedIn provider request failed."))
                .andExpect(jsonPath("$.message").value(Matchers.not(
                        Matchers.containsString("provider private details"))));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));
        mockMvc.perform(get("/api/v1/linkedin/connection").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void userInfoFailureConsumesStateAndCannotBeRetried() throws Exception {
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
        when(linkedInOAuthClient.exchangeAuthorizationCode("authorization-code"))
                .thenReturn(new LinkedInOAuthClient.AccessToken(
                        "raw-access-token", 3600, Optional.empty(), Optional.empty()));
        when(linkedInOAuthClient.userInfo("raw-access-token"))
                .thenThrow(new LinkedInProviderException("provider private details"));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("LINKEDIN_PROVIDER_ERROR"));

        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(session)
                        .param("state", state)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINKEDIN_AUTHORIZATION_FAILED"));
        verify(linkedInOAuthClient).exchangeAuthorizationCode("authorization-code");
        verify(linkedInOAuthClient).userInfo("raw-access-token");
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
                        .session(firstSession)
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
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/linkedin/connection").session(firstSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/linkedin/connection").session(firstSession).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/linkedin/oauth/callback")
                        .session(firstSession)
                        .param("state", pendingState)
                        .param("code", "authorization-code"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/linkedin/connection").session(firstSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
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
