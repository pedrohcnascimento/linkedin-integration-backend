package com.example.linkedinagent.application.ports.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OAuthTransactionPort {

    void create(UUID appUserId, String stateHash, String scopes, Instant createdAt, Instant expiresAt);

    Optional<PendingOAuthTransaction> consume(String stateHash, Instant consumedAt);

    void cancelPendingForUser(UUID appUserId);

    record PendingOAuthTransaction(UUID appUserId, String scopes, Instant expiresAt) {
    }
}
