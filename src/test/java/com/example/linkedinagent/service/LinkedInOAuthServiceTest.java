package com.example.linkedinagent.service;

import com.example.linkedinagent.client.LinkedInOAuthClient;
import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.LinkedInAuthorization;
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
        when(fixture.tokenCipher.encrypt("access-token"))
                .thenThrow(new TokenEncryptionException("encryption unavailable"));

        assertThatThrownBy(() -> fixture.service.complete(fixture.start(), "authorization-code"))
                .isInstanceOf(TokenEncryptionException.class);
        assertReplayRejected(fixture);
        verify(fixture.authorizationRepository, never()).saveAndFlush(any(LinkedInAuthorization.class));
    }

    @Test
    void persistenceFailureConsumesStateAndCannotBeRetried() {
        Fixture fixture = new Fixture();
        when(fixture.tokenCipher.encrypt("access-token")).thenReturn("encrypted-token");
        doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.authorizationRepository)
                .saveAndFlush(any(LinkedInAuthorization.class));

        assertThatThrownBy(() -> fixture.service.complete(fixture.start(), "authorization-code"))
                .isInstanceOf(IllegalStateException.class);
        assertReplayRejected(fixture);
        verify(fixture.authorizationRepository).saveAndFlush(any(LinkedInAuthorization.class));
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
                            NOW.plusSeconds(600))));
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
