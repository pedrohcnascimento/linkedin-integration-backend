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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(LinkedInOAuthService.class);
    private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration CONSUMED_STATE_RETENTION = Duration.ofHours(24);
    private static final int STATE_LENGTH_BYTES = 32;

    private final OAuthTransactionRepository transactionRepository;
    private final LinkedInAuthorizationRepository authorizationRepository;
    private final LinkedInOAuthClient linkedinOAuthClient;
    private final TokenCipher tokenCipher;
    private final AppProperties.Linkedin linkedinProperties;
    private final Clock clock;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public LinkedInOAuthService(
            OAuthTransactionRepository transactionRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            LinkedInOAuthClient linkedinOAuthClient,
            TokenCipher tokenCipher,
            AppProperties appProperties,
            Clock clock) {
        this(transactionRepository, authorizationRepository, linkedinOAuthClient, tokenCipher, appProperties, clock, null);
    }

    @Autowired
    public LinkedInOAuthService(
            OAuthTransactionRepository transactionRepository,
            LinkedInAuthorizationRepository authorizationRepository,
            LinkedInOAuthClient linkedinOAuthClient,
            TokenCipher tokenCipher,
            AppProperties appProperties,
            Clock clock,
            AuditService auditService) {
        this.transactionRepository = transactionRepository;
        this.authorizationRepository = authorizationRepository;
        this.linkedinOAuthClient = linkedinOAuthClient;
        this.tokenCipher = tokenCipher;
        this.linkedinProperties = appProperties.getLinkedin();
        this.clock = clock;
        this.auditService = auditService;
    }

    public URI start(UUID appUserId) {
        tokenCipher.validateConfiguration();
        Instant now = clock.instant();
        transactionRepository.deleteExpiredOrConsumedBefore(now, now.minus(CONSUMED_STATE_RETENTION));
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
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "LINKEDIN_OAUTH_STARTED",
                    "OAUTH_TRANSACTION",
                    transaction.getId().toString(),
                    "STARTED",
                    "scopes=" + scopes);
        }
        log.info("Started LinkedIn OAuth flow for userId={} transactionId={}", appUserId, transaction.getId());
        return authorizationUri;
    }

    public LinkedInConnection complete(String state, String authorizationCode) {
        return complete(null, state, authorizationCode);
    }

    public LinkedInConnection complete(UUID expectedUserId, String state, String authorizationCode) {
        if (state == null || state.isBlank()) {
            throw new LinkedInOAuthException("OAuth state is missing or invalid.");
        }
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new LinkedInOAuthException("LinkedIn authorization was not completed.");
        }

        Instant now = clock.instant();
        OAuthTransaction transaction = consumeValidTransaction(expectedUserId, state, now);
        long startedNanos = System.nanoTime();

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
        long latencyMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "LINKEDIN_OAUTH_COMPLETED",
                    "LINKEDIN_AUTHORIZATION",
                    authorization.getId().toString(),
                    "CONNECTED",
                    "memberSubject=" + member.subject() + ";scopes=" + scopes + ";latencyMs=" + latencyMs);
        }
        log.info("Completed LinkedIn OAuth connection for userId={} authorizationId={} expiresAt={} latencyMs={}",
                appUserId, authorization.getId(), expiresAt, latencyMs);
        return new LinkedInConnection(
                member.subject(), scopes, LinkedInAuthorization.STATUS_ACTIVE, expiresAt, now);
    }

    public void rejectAuthorization(String state) {
        rejectAuthorization(null, state);
    }

    public void rejectAuthorization(UUID expectedUserId, String state) {
        if (state == null || state.isBlank()) {
            throw new LinkedInOAuthException("OAuth state is missing or invalid.");
        }
        OAuthTransaction transaction = consumeValidTransaction(expectedUserId, state, clock.instant());
        UUID appUserId = transaction.getAppUser().getId();
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "LINKEDIN_OAUTH_REJECTED",
                    "OAUTH_TRANSACTION",
                    transaction.getId().toString(),
                    "DENIED",
                    null);
        }
        log.info("LinkedIn OAuth authorization denied for userId={} transactionId={}",
                appUserId, transaction.getId());
    }

    @Transactional(readOnly = true)
    public Optional<LinkedInConnection> connection(UUID appUserId) {
        return authorizationRepository.findByAppUser_Id(appUserId)
                .map(authorization -> authorization.toConnectionAt(clock.instant()));
    }

    @Transactional
    public void disconnect(UUID appUserId) {
        transactionRepository.deletePendingForUser(appUserId);
        transactionRepository.deleteAllForUser(appUserId);
        Optional<LinkedInAuthorization> existing = authorizationRepository.findByAppUser_Id(appUserId);
        String removedId = existing
                .map(LinkedInAuthorization::getId)
                .map(UUID::toString)
                .orElse(null);
        existing.ifPresent(authorizationRepository::delete);
        if (auditService != null) {
            auditService.recordForUser(
                    appUserId,
                    "LINKEDIN_CONNECTION_DISCONNECTED",
                    "LINKEDIN_AUTHORIZATION",
                    removedId,
                    "DISCONNECTED",
                    "localTokensRemoved=" + existing.isPresent() + ";remoteRevocation=not_supported");
        }
        log.info("Disconnected local LinkedIn authorization for userId={} hadActiveRecord={}",
                appUserId, existing.isPresent());
    }

    private OAuthTransaction consumeValidTransaction(UUID expectedUserId, String state, Instant now) {
        String stateHash = hashState(state);
        if (transactionRepository.markConsumedIfUnused(stateHash, now) != 1) {
            throw new LinkedInOAuthException("OAuth state is missing, expired, or already used.");
        }
        OAuthTransaction transaction = transactionRepository.findByStateHash(stateHash)
                .orElseThrow(() -> new IllegalStateException("Consumed OAuth transaction could not be loaded."));
        if (!transaction.isUnexpiredAt(now)) {
            throw new LinkedInOAuthException("OAuth state is missing, expired, or already used.");
        }
        if (expectedUserId != null && !expectedUserId.equals(transaction.getAppUser().getId())) {
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
