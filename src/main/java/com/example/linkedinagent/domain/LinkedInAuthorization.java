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
@Table(name = "linkedin_authorization")
public class LinkedInAuthorization {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_DISCONNECTED = "DISCONNECTED";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false, unique = true)
    private AppUser appUser;

    @Column(name = "member_subject", columnDefinition = "TEXT")
    private String memberSubject;

    @Column(name = "encrypted_access_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    @Column(nullable = false)
    private String scopes;

    @Column(name = "expires_at", columnDefinition = "TEXT")
    private Instant expiresAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    private Instant updatedAt;

    public static LinkedInAuthorization createForUser(AppUser appUser, Instant now) {
        LinkedInAuthorization authorization = new LinkedInAuthorization();
        authorization.setId(UUID.randomUUID());
        authorization.setAppUser(appUser);
        authorization.setCreatedAt(now);
        return authorization;
    }

    public void refreshTokens(
            String memberSubject,
            String encryptedAccessToken,
            String encryptedRefreshToken,
            String scopes,
            Instant expiresAt,
            Instant now) {
        this.memberSubject = memberSubject;
        this.encryptedAccessToken = encryptedAccessToken;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.scopes = scopes;
        this.expiresAt = expiresAt;
        this.status = STATUS_ACTIVE;
        this.updatedAt = now;
    }

    public boolean isUsableAt(Instant now) {
        return STATUS_ACTIVE.equals(status) && !isExpiredAt(now);
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public LinkedInConnection toConnectionAt(Instant now) {
        String effectiveStatus = isExpiredAt(now) ? STATUS_EXPIRED : status;
        return new LinkedInConnection(memberSubject, scopes, effectiveStatus, expiresAt, updatedAt);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getAppUser() { return appUser; }
    public void setAppUser(AppUser appUser) { this.appUser = appUser; }
    public String getMemberSubject() { return memberSubject; }
    public void setMemberSubject(String memberSubject) { this.memberSubject = memberSubject; }
    public String getEncryptedAccessToken() { return encryptedAccessToken; }
    public void setEncryptedAccessToken(String encryptedAccessToken) { this.encryptedAccessToken = encryptedAccessToken; }
    public String getEncryptedRefreshToken() { return encryptedRefreshToken; }
    public void setEncryptedRefreshToken(String encryptedRefreshToken) { this.encryptedRefreshToken = encryptedRefreshToken; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
