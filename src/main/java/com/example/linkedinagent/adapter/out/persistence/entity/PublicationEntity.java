package com.example.linkedinagent.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "publication")
public class PublicationEntity {
    @Id
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUserEntity appUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "draft_id")
    private ContentDraftEntity draft;

    @Column(name = "external_post_id", columnDefinition = "TEXT")
    private String externalPostId;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "TEXT")
    private String requestFingerprint;

    @Column(nullable = false)
    private String status;

    @Column(name = "failure_code", columnDefinition = "TEXT")
    private String failureCode;

    @Column(name = "published_at", columnDefinition = "TEXT")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUserEntity getAppUser() { return appUser; }
    public void setAppUser(AppUserEntity appUser) { this.appUser = appUser; }
    public ContentDraftEntity getDraft() { return draft; }
    public void setDraft(ContentDraftEntity draft) { this.draft = draft; }
    public String getExternalPostId() { return externalPostId; }
    public void setExternalPostId(String externalPostId) { this.externalPostId = externalPostId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
