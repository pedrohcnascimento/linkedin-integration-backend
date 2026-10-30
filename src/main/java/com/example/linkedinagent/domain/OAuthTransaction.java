package com.example.linkedinagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_transaction")
public class OAuthTransaction {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(name = "state_hash", nullable = false, unique = true)
    private String stateHash;

    @Column(nullable = false)
    private String scopes;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TEXT")
    private Instant expiresAt;

    @Column(name = "consumed_at", columnDefinition = "TEXT")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    public static OAuthTransaction start(
            AppUser appUser,
            String stateHash,
            String scopes,
            Instant createdAt,
            Instant expiresAt) {
        OAuthTransaction transaction = new OAuthTransaction();
        transaction.setId(UUID.randomUUID());
        transaction.setAppUser(appUser);
        transaction.setStateHash(stateHash);
        transaction.setScopes(scopes);
        transaction.setCreatedAt(createdAt);
        transaction.setExpiresAt(expiresAt);
        return transaction;
    }

    public boolean isUnexpiredAt(Instant now) {
        return expiresAt != null && expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getAppUser() { return appUser; }
    public void setAppUser(AppUser appUser) { this.appUser = appUser; }
    public String getStateHash() { return stateHash; }
    public void setStateHash(String stateHash) { this.stateHash = stateHash; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
