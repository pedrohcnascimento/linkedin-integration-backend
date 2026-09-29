package com.example.linkedinagent.application.linkedin;

import com.example.linkedinagent.application.ports.out.LinkedInAuthorizationPort;
import com.example.linkedinagent.application.ports.out.LinkedInOAuthClient;
import com.example.linkedinagent.application.ports.out.OAuthTransactionPort;
import com.example.linkedinagent.application.ports.out.TokenCipherPort;
import com.example.linkedinagent.config.AppProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class LinkedInOAuthService {

    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final int STATE_LENGTH_BYTES = 32;

    private final OAuthTransactionPort transactionPort;
    private final LinkedInAuthorizationPort authorizationPort;
    private final LinkedInOAuthClient linkedinOAuthClient;
    private final TokenCipherPort tokenCipher;
    private final AppProperties.Linkedin linkedinProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public LinkedInOAuthService(
            OAuthTransactionPort transactionPort,
            LinkedInAuthorizationPort authorizationPort,
            LinkedInOAuthClient linkedinOAuthClient,
            TokenCipherPort tokenCipher,
            AppProperties appProperties,
            Clock clock) {
        this.transactionPort = transactionPort;
        this.authorizationPort = authorizationPort;
        this.linkedinOAuthClient = linkedinOAuthClient;
        this.tokenCipher = tokenCipher;
        this.linkedinProperties = appProperties.getLinkedin();
        this.clock = clock;
    }

    public URI start(UUID appUserId) {
        tokenCipher.validateConfiguration();
        Instant now = clock.instant();
        String state = newState();
        String scopes = linkedinProperties.getScopes();
        URI authorizationUri = linkedinOAuthClient.authorizationUri(state, scopes);
        transactionPort.create(appUserId, hashState(state), scopes, now, now.plus(STATE_LIFETIME));
        return authorizationUri;
    }

    public LinkedInConnection complete(String state, String authorizationCode) {
        if (state == null || state.isBlank()) {
            throw new LinkedInOAuthException("OAuth state is missing or invalid.");
        }
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new LinkedInOAuthException("LinkedIn authorization was not completed.");
        }

        Instant now = clock.instant();
        OAuthTransactionPort.PendingOAuthTransaction transaction =
                transactionPort.consume(hashState(state), now)
                        .filter(pending -> pending.expiresAt().isAfter(now))
                        .orElseThrow(() -> new LinkedInOAuthException("OAuth state is missing, expired, or already used."));

        LinkedInOAuthClient.AccessToken accessToken =
                linkedinOAuthClient.exchangeAuthorizationCode(authorizationCode);
        if (accessToken.value() == null || accessToken.value().isBlank() || accessToken.expiresInSeconds() <= 0) {
            throw new LinkedInOAuthException("LinkedIn returned an invalid access token response.");
        }

        LinkedInOAuthClient.LinkedInMember member =
                linkedinOAuthClient.userInfo(accessToken.value());
        if (member.subject() == null || member.subject().isBlank()) {
            throw new LinkedInOAuthException("LinkedIn did not return a valid member identifier.");
        }

        Instant expiresAt;
        try {
            expiresAt = now.plusSeconds(accessToken.expiresInSeconds());
        } catch (ArithmeticException | DateTimeException exception) {
            throw new LinkedInOAuthException("LinkedIn returned an invalid token expiration.");
        }
        String scopes = accessToken.scopes().filter(value -> !value.isBlank()).orElse(transaction.scopes());
        String encryptedRefreshToken = accessToken.refreshToken()
                .filter(value -> !value.isBlank())
                .map(tokenCipher::encrypt)
                .orElse(null);

        authorizationPort.save(
                transaction.appUserId(),
                member.subject(),
                tokenCipher.encrypt(accessToken.value()),
                encryptedRefreshToken,
                scopes,
                expiresAt,
                now);
        return new LinkedInConnection(member.subject(), scopes, "ACTIVE", expiresAt, now);
    }

    public void rejectAuthorization(String state) {
        if (state == null || state.isBlank()) {
            throw new LinkedInOAuthException("OAuth state is missing or invalid.");
        }
        Instant now = clock.instant();
        transactionPort.consume(hashState(state), now)
                .filter(pending -> pending.expiresAt().isAfter(now))
                .orElseThrow(() -> new LinkedInOAuthException("OAuth state is missing, expired, or already used."));
    }

    public Optional<LinkedInConnection> connection(UUID appUserId) {
        return authorizationPort.findByAppUserId(appUserId)
                .map(connection -> {
                    if (connection.expiresAt() != null && !connection.expiresAt().isAfter(clock.instant())) {
                        return new LinkedInConnection(
                                connection.memberSubject(),
                                connection.scopes(),
                                "EXPIRED",
                                connection.expiresAt(),
                                connection.connectedAt());
                    }
                    return connection;
                });
    }

    public void disconnect(UUID appUserId) {
        transactionPort.cancelPendingForUser(appUserId);
        authorizationPort.deleteByAppUserId(appUserId);
    }

    private String newState() {
        byte[] bytes = new byte[STATE_LENGTH_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashState(String state) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
