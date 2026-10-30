package com.example.linkedinagent.service;

import com.example.linkedinagent.client.LinkedInOAuthClient;
import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.LinkedInAuthorization;
import com.example.linkedinagent.domain.LinkedInConnection;
import com.example.linkedinagent.domain.OAuthTransaction;
import com.example.linkedinagent.exception.LinkedInOAuthException;
import com.example.linkedinagent.repository.LinkedInAuthorizationRepository;
import com.example.linkedinagent.repository.OAuthTransactionRepository;
import com.example.linkedinagent.security.TokenCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final OAuthTransactionRepository transactionRepository;
    private final LinkedInAuthorizationRepository authorizationRepository;
    private final LinkedInOAuthClient linkedinOAuthClient;
    private final TokenCipher tokenCipher;
    private final AppProperties.Linkedin linkedinProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public LinkedInOAuthService(
            OAuthTransactionRepository transactionRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            LinkedInOAuthClient linkedinOAuthClient,
            TokenCipher tokenCipher,
            AppProperties appProperties,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.authorizationRepository = authorizationRepository;
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
        OAuthTransaction transaction = OAuthTransaction.start(
                AppUser.reference(appUserId),
                hashState(state),
                scopes,
                now,
                now.plus(STATE_LIFETIME));
        transactionRepository.saveAndFlush(transaction);
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
        OAuthTransaction transaction = consumeValidTransaction(state, now);

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
        String scopes = accessToken.scopes().filter(value -> !value.isBlank()).orElse(transaction.getScopes());
        String encryptedRefreshToken = accessToken.refreshToken()
                .filter(value -> !value.isBlank())
                .map(tokenCipher::encrypt)
                .orElse(null);
        String encryptedAccessToken = tokenCipher.encrypt(accessToken.value());

        UUID appUserId = transaction.getAppUser().getId();
        LinkedInAuthorization authorization = authorizationRepository.findByAppUser_Id(appUserId)
                .orElseGet(() -> LinkedInAuthorization.createForUser(AppUser.reference(appUserId), now));
        authorization.refreshTokens(
                member.subject(),
                encryptedAccessToken,
                encryptedRefreshToken,
                scopes,
                expiresAt,
                now);
        authorizationRepository.saveAndFlush(authorization);
        return new LinkedInConnection(
                member.subject(), scopes, LinkedInAuthorization.STATUS_ACTIVE, expiresAt, now);
    }

    public void rejectAuthorization(String state) {
        if (state == null || state.isBlank()) {
            throw new LinkedInOAuthException("OAuth state is missing or invalid.");
        }
        consumeValidTransaction(state, clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<LinkedInConnection> connection(UUID appUserId) {
        return authorizationRepository.findByAppUser_Id(appUserId)
                .map(authorization -> authorization.toConnectionAt(clock.instant()));
    }

    @Transactional
    public void disconnect(UUID appUserId) {
        transactionRepository.deletePendingForUser(appUserId);
        authorizationRepository.findByAppUser_Id(appUserId).ifPresent(authorizationRepository::delete);
    }

    private OAuthTransaction consumeValidTransaction(String state, Instant now) {
        String stateHash = hashState(state);
        if (transactionRepository.markConsumedIfUnused(stateHash, now) != 1) {
            throw new LinkedInOAuthException("OAuth state is missing, expired, or already used.");
        }
        OAuthTransaction transaction = transactionRepository.findByStateHash(stateHash)
                .orElseThrow(() -> new IllegalStateException("Consumed OAuth transaction could not be loaded."));
        if (!transaction.isUnexpiredAt(now)) {
            throw new LinkedInOAuthException("OAuth state is missing, expired, or already used.");
        }
        return transaction;
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
