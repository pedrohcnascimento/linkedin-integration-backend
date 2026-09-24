package com.example.linkedinagent.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_transaction")
public class OAuthTransactionEntity {
    @Id
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUserEntity appUser;

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

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUserEntity getAppUser() { return appUser; }
    public void setAppUser(AppUserEntity appUser) { this.appUser = appUser; }
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
