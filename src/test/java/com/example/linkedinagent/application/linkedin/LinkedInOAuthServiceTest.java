package com.example.linkedinagent.application.linkedin;

import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import com.example.linkedinagent.application.ports.out.LinkedInOAuthClient;
import com.example.linkedinagent.application.ports.out.OAuthTransactionPort;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkedInOAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T20:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();

    private RecordingTransactions transactions;
    private RecordingAuthorizations authorizations;
    private StubOAuthClient oauthClient;
    private LinkedInOAuthService service;

    @BeforeEach
    void setUp() {
        transactions = new RecordingTransactions();
        authorizations = new RecordingAuthorizations();
        oauthClient = new StubOAuthClient();
        AppProperties properties = appProperties();
        TokenCipherPort tokenCipher = new TokenCipherPort() {
            @Override
            public void validateConfiguration() {
            }

            @Override
            public String encrypt(String value) {
                return "encrypted:" + value;
            }

            @Override
            public String decrypt(String value) {
                return value.replaceFirst("^encrypted:", "");
            }
        };
        service = new LinkedInOAuthService(
                transactions,
                authorizations,
                oauthClient,
                tokenCipher,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startCreatesHashedStateBoundToUserWithShortExpiration() {
        URI authorizationUri = service.start(USER_ID);

        assertThat(authorizationUri).isEqualTo(URI.create("https://linkedin.example/authorize"));
        assertThat(transactions.appUserId).isEqualTo(USER_ID);
        assertThat(transactions.stateHash).isNotBlank().isNotEqualTo(oauthClient.state);
        assertThat(oauthClient.state).hasSize(43);
        assertThat(transactions.scopes).isEqualTo("openid profile w_member_social");
        assertThat(transactions.createdAt).isEqualTo(NOW);
        assertThat(transactions.expiresAt).isEqualTo(NOW.plusSeconds(600));
    }

    @Test
    void startValidatesEncryptionConfigurationBeforeCreatingOAuthTransaction() {
        TokenCipherPort invalidCipher = new TokenCipherPort() {
            @Override
            public void validateConfiguration() {
                throw new TokenEncryptionException("invalid test key");
            }

            @Override
            public String encrypt(String value) {
                return value;
            }

            @Override
            public String decrypt(String encryptedValue) {
                return encryptedValue;
            }
        };
        LinkedInOAuthService serviceWithInvalidCipher = new LinkedInOAuthService(
                transactions,
                authorizations,
                oauthClient,
                invalidCipher,
                appProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> serviceWithInvalidCipher.start(USER_ID))
                .isInstanceOf(TokenEncryptionException.class);

        assertThat(transactions.appUserId).isNull();
        assertThat(oauthClient.state).isNull();
    }

    @Test
    void completesAuthorizationAndEncryptsTokensForTheUserBoundToState() {
        transactions.pending = new OAuthTransactionPort.PendingOAuthTransaction(
                USER_ID, "openid profile w_member_social", NOW.plusSeconds(600));

        LinkedInConnection connection = service.complete("valid-state", "authorization-code");

        assertThat(oauthClient.exchangedCode).isEqualTo("authorization-code");
        assertThat(oauthClient.userInfoToken).isEqualTo("raw-access-token");
        assertThat(authorizations.appUserId).isEqualTo(USER_ID);
        assertThat(authorizations.memberSubject).isEqualTo("linkedin-subject");
        assertThat(authorizations.encryptedAccessToken).isEqualTo("encrypted:raw-access-token");
        assertThat(authorizations.encryptedRefreshToken).isNull();
        assertThat(authorizations.scopes).isEqualTo("openid profile w_member_social");
        assertThat(authorizations.expiresAt).isEqualTo(NOW.plusSeconds(3600));
        assertThat(connection.status()).isEqualTo("ACTIVE");
        assertThat(connection.memberSubject()).isEqualTo("linkedin-subject");
    }

    @Test
    void rejectsMissingExpiredOrAlreadyConsumedStateBeforeCallingProvider() {
        assertThatThrownBy(() -> service.complete("invalid-state", "authorization-code"))
                .isInstanceOf(LinkedInOAuthException.class);
        assertThat(oauthClient.exchangedCode).isNull();

        transactions.pending = new OAuthTransactionPort.PendingOAuthTransaction(
                USER_ID, "openid profile", NOW);
        assertThatThrownBy(() -> service.complete("expired-state", "authorization-code"))
                .isInstanceOf(LinkedInOAuthException.class);
        assertThat(oauthClient.exchangedCode).isNull();
    }

    @Test
    void failedProviderExchangeDoesNotReplaceExistingAuthorization() {
        transactions.pending = new OAuthTransactionPort.PendingOAuthTransaction(
                USER_ID, "openid profile", NOW.plusSeconds(600));
        oauthClient.exchangeFailure = new LinkedInProviderException("provider unavailable");

        assertThatThrownBy(() -> service.complete("valid-state", "authorization-code"))
                .isInstanceOf(LinkedInProviderException.class);

        assertThat(authorizations.saveCount).isZero();
    }

    @Test
    void connectionReportsExpiredTokensWithoutExposingCredentials() {
        authorizations.connection = new LinkedInConnection(
                "linkedin-subject", "openid profile", "ACTIVE", NOW, NOW.minusSeconds(10));

        assertThat(service.connection(USER_ID)).contains(new LinkedInConnection(
                "linkedin-subject", "openid profile", "EXPIRED", NOW, NOW.minusSeconds(10)));
    }

    private static AppProperties appProperties() {
        AppProperties properties = new AppProperties();
        properties.getLinkedin().setClientId("test-client");
        properties.getLinkedin().setScopes("openid profile w_member_social");
        return properties;
    }

    private static class RecordingTransactions implements OAuthTransactionPort {
        private UUID appUserId;
        private String stateHash;
        private String scopes;
        private Instant createdAt;
        private Instant expiresAt;
        private PendingOAuthTransaction pending;

        @Override
        public void create(UUID userId, String hash, String requestedScopes, Instant created, Instant expires) {
            appUserId = userId;
            stateHash = hash;
            scopes = requestedScopes;
            createdAt = created;
            expiresAt = expires;
        }

        @Override
        public Optional<PendingOAuthTransaction> consume(String hash, Instant consumedAt) {
            if (pending == null || (stateHash != null && !stateHash.equals(hash))) {
                return Optional.empty();
            }
            PendingOAuthTransaction result = pending;
            pending = null;
            return Optional.of(result);
        }

        @Override
        public void cancelPendingForUser(UUID userId) {
            pending = null;
        }
    }

    private static class RecordingAuthorizations implements LinkedInAuthorizationPort {
        private int saveCount;
        private UUID appUserId;
        private String memberSubject;
        private String encryptedAccessToken;
        private String encryptedRefreshToken;
        private String scopes;
        private Instant expiresAt;
        private LinkedInConnection connection;

        @Override
        public void save(
                UUID userId,
                String subject,
                String accessToken,
                String refreshToken,
                String grantedScopes,
                Instant expiry,
                Instant now) {
            saveCount++;
            appUserId = userId;
            memberSubject = subject;
            encryptedAccessToken = accessToken;
            encryptedRefreshToken = refreshToken;
            scopes = grantedScopes;
            expiresAt = expiry;
        }

        @Override
        public Optional<LinkedInConnection> findByAppUserId(UUID userId) {
            return Optional.ofNullable(connection);
        }

        @Override
        public void deleteByAppUserId(UUID userId) {
            connection = null;
        }
    }

    private static class StubOAuthClient implements LinkedInOAuthClient {
        private String state;
        private String exchangedCode;
        private String userInfoToken;
        private RuntimeException exchangeFailure;

        @Override
        public URI authorizationUri(String value, String scopes) {
            state = value;
            return URI.create("https://linkedin.example/authorize");
        }

        @Override
        public AccessToken exchangeAuthorizationCode(String code) {
            if (exchangeFailure != null) {
                throw exchangeFailure;
            }
            exchangedCode = code;
            return new AccessToken("raw-access-token", 3600, Optional.empty(), Optional.empty());
        }

        @Override
        public LinkedInMember userInfo(String accessToken) {
            userInfoToken = accessToken;
            return new LinkedInMember("linkedin-subject");
        }
    }
}
