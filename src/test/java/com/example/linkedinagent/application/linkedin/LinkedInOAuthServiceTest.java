package com.example.linkedinagent.application.linkedin;

import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import com.example.linkedinagent.application.ports.out.LinkedInOAuthClient;
import com.example.linkedinagent.application.ports.out.OAuthTransactionPort;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.exception.LinkedInOAuthException;
import com.example.linkedinagent.exception.TokenEncryptionException;
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
import static org.mockito.Mockito.mock;
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
        verify(fixture.authorizationPort, org.mockito.Mockito.never())
                .save(any(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void persistenceFailureConsumesStateAndCannotBeRetried() {
        Fixture fixture = new Fixture();
        when(fixture.tokenCipher.encrypt("access-token")).thenReturn("encrypted-token");
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.authorizationPort)
                .save(any(), anyString(), anyString(), any(), anyString(), any(), any());

        assertThatThrownBy(() -> fixture.service.complete(fixture.start(), "authorization-code"))
                .isInstanceOf(IllegalStateException.class);
        assertReplayRejected(fixture);
        verify(fixture.authorizationPort)
                .save(any(), anyString(), anyString(), any(), anyString(), any(), any());
    }

    private void assertReplayRejected(Fixture fixture) {
        assertThatThrownBy(() -> fixture.service.complete(fixture.state.get(), "authorization-code"))
                .isInstanceOf(LinkedInOAuthException.class)
                .hasMessage("OAuth state is missing, expired, or already used.");
    }

    private static final class Fixture {
        private final OAuthTransactionPort transactionPort = mock(OAuthTransactionPort.class);
        private final LinkedInAuthorizationPort authorizationPort = mock(LinkedInAuthorizationPort.class);
        private final LinkedInOAuthClient oauthClient = mock(LinkedInOAuthClient.class);
        private final TokenCipherPort tokenCipher = mock(TokenCipherPort.class);
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
            when(transactionPort.consume(anyString(), any(Instant.class)))
                    .thenAnswer(invocation -> consumed.compareAndSet(false, true)
                            ? Optional.of(new OAuthTransactionPort.PendingOAuthTransaction(
                                    USER_ID, "openid profile", NOW.plusSeconds(600)))
                            : Optional.empty());
            when(oauthClient.exchangeAuthorizationCode("authorization-code"))
                    .thenReturn(new LinkedInOAuthClient.AccessToken(
                            "access-token", 3600, Optional.empty(), Optional.empty()));
            when(oauthClient.userInfo("access-token"))
                    .thenReturn(new LinkedInOAuthClient.LinkedInMember("member-subject"));

            service = new LinkedInOAuthService(
                    transactionPort,
                    authorizationPort,
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
