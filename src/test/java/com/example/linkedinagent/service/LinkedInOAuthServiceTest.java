package com.example.linkedinagent.service;

import com.example.linkedinagent.client.LinkedInOAuthClient;
import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.LinkedInConnection;
import com.example.linkedinagent.domain.OAuthTransaction;
import com.example.linkedinagent.exception.LinkedInOAuthException;
import com.example.linkedinagent.exception.TokenEncryptionException;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.repository.OAuthTransactionRepository;
import com.example.linkedinagent.security.TokenCipher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LinkedInOAuthServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void encryptionFailureConsumesStateAndCannotBeRetried() {
        Fixture fixture = new Fixture();
        when(fixture.tokenCipher.encrypt("access-token", USER_ID.toString()))
                .thenThrow(new TokenEncryptionException("encryption unavailable"));

        assertThatThrownBy(() -> fixture.service.complete(fixture.start(), "authorization-code"))
                .isInstanceOf(TokenEncryptionException.class);
        assertReplayRejected(fixture);
        verify(fixture.authorizationRepository, never()).saveAndFlush(any(LinkedInAuthorization.class));
    }

    @Test
    void persistenceFailureConsumesStateAndCannotBeRetried() {
        Fixture fixture = new Fixture();
        when(fixture.tokenCipher.encrypt("access-token", USER_ID.toString())).thenReturn("encrypted-token");
        doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.authorizationRepository)
                .saveAndFlush(any(LinkedInAuthorization.class));

        assertThatThrownBy(() -> fixture.service.complete(fixture.start(), "authorization-code"))
                .isInstanceOf(IllegalStateException.class);
        assertReplayRejected(fixture);
        verify(fixture.authorizationRepository).saveAndFlush(any(LinkedInAuthorization.class));
    }

    @Test
    void expiredOAuthStateIsRejectedAndConnectionReportsExpiredWhenTokenHasLapsed() {
        Fixture fixture = new Fixture(NOW.minusSeconds(1));

        assertThatThrownBy(() -> fixture.service.complete(fixture.start(), "authorization-code"))
                .isInstanceOf(LinkedInOAuthException.class)
                .hasMessage("OAuth state is missing, expired, or already used.");
        verify(fixture.oauthClient, never()).exchangeAuthorizationCode(anyString());

        LinkedInAuthorization lapsed = LinkedInAuthorization.createForUser(AppUser.reference(USER_ID), NOW.minusSeconds(3600));
        lapsed.refreshTokens("member-subject", "enc-token", null, "openid profile", NOW, NOW.minusSeconds(3600));
        when(fixture.authorizationRepository.findByAppUser_Id(USER_ID)).thenReturn(Optional.of(lapsed));

        Optional<LinkedInConnection> connection = fixture.service.connection(USER_ID);
        assertThat(connection).isPresent();
        assertThat(connection.get().status()).isEqualTo(LinkedInAuthorization.STATUS_EXPIRED);
    }

    @Test
    void completeUpdatesExistingAuthorizationOnReconnectWithoutPersistingRefreshTokenAndDisconnectRemovesLocalState() {
        Fixture fixture = new Fixture();
        LinkedInAuthorization existing = LinkedInAuthorization.createForUser(
                AppUser.reference(USER_ID), NOW.minusSeconds(7200));
        when(fixture.authorizationRepository.findByAppUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(fixture.tokenCipher.encrypt("access-token", USER_ID.toString())).thenReturn("enc-access");
        when(fixture.oauthClient.exchangeAuthorizationCode("authorization-code"))
                .thenReturn(new LinkedInOAuthClient.AccessToken(
                        "access-token", 3600, Optional.of("refresh-token"), Optional.empty()));

        LinkedInConnection connection = fixture.service.complete(fixture.start(), "authorization-code");

        assertThat(connection.status()).isEqualTo(LinkedInAuthorization.STATUS_ACTIVE);
        assertThat(connection.scopes()).isEqualTo("openid profile");
        assertThat(existing.getEncryptedAccessToken()).isEqualTo("enc-access");
        assertThat(existing.getEncryptedRefreshToken()).isNull();
        verify(fixture.authorizationRepository).saveAndFlush(existing);

        fixture.service.disconnect(USER_ID);
        verify(fixture.transactionRepository).deletePendingForUser(USER_ID);
        verify(fixture.authorizationRepository).delete(existing);
    }

    private void assertReplayRejected(Fixture fixture) {
        assertThatThrownBy(() -> fixture.service.complete(fixture.state.get(), "authorization-code"))
                .isInstanceOf(LinkedInOAuthException.class)
                .hasMessage("OAuth state is missing, expired, or already used.");
    }

    private static final class Fixture {
        private final OAuthTransactionRepository transactionRepository = mock(OAuthTransactionRepository.class);
        private final LinkedInAuthorizationRepository authorizationRepository = mock(LinkedInAuthorizationRepository.class);
        private final LinkedInOAuthClient oauthClient = mock(LinkedInOAuthClient.class);
        private final TokenCipher tokenCipher = mock(TokenCipher.class);
        private final AtomicReference<String> state = new AtomicReference<>();
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final LinkedInOAuthService service;

        private Fixture() {
            this(NOW.plusSeconds(600));
        }

        private Fixture(Instant stateExpiresAt) {
            AppProperties properties = new AppProperties();
            properties.getLinkedin().setScopes("openid profile");
            when(oauthClient.authorizationUri(anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        state.set(invocation.getArgument(0));
                        return URI.create("https://www.linkedin.com/oauth/v2/authorization");
                    });
            when(transactionRepository.markConsumedIfUnused(anyString(), any(Instant.class)))
                    .thenAnswer(invocation -> consumed.compareAndSet(false, true) ? 1 : 0);
            when(transactionRepository.findByStateHash(anyString()))
                    .thenAnswer(invocation -> Optional.of(OAuthTransaction.start(
                            AppUser.reference(USER_ID),
                            invocation.getArgument(0),
                            "openid profile",
                            NOW,
                            stateExpiresAt)));
            when(authorizationRepository.findByAppUser_Id(USER_ID)).thenReturn(Optional.empty());
            when(oauthClient.exchangeAuthorizationCode("authorization-code"))
                    .thenReturn(new LinkedInOAuthClient.AccessToken(
                            "access-token", 3600, Optional.empty(), Optional.empty()));
            when(oauthClient.userInfo("access-token"))
                    .thenReturn(new LinkedInOAuthClient.LinkedInMember("member-subject"));

            service = new LinkedInOAuthService(
                    transactionRepository,
                    authorizationRepository,
                    oauthClient,
                    tokenCipher,
                    properties,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private String start() {
            service.start(USER_ID);
            return state.get();
        }
    }
}
