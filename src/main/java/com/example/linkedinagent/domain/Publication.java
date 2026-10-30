package com.example.linkedinagent.domain;

import com.example.linkedinagent.exception.LinkedInPublishingException;
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
@Table(name = "publication")
public class Publication {

    public static final String STATUS_PUBLISHING = "PUBLISHING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    public static final String FAILURE_RESULT_UNKNOWN = "LINKEDIN_RESULT_UNKNOWN";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "draft_id")
    private ContentDraft draft;

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

    public static Publication reserve(AppUser owner, ContentDraft draft, String requestFingerprint, Instant now) {
        Publication publication = new Publication();
        publication.setId(UUID.randomUUID());
        publication.setAppUser(owner);
        publication.setDraft(draft);
        publication.setRequestFingerprint(requestFingerprint);
        publication.setStatus(STATUS_PUBLISHING);
        publication.setCreatedAt(now);
        return publication;
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    public void markPublished(String externalPostId, Instant now) {
        this.externalPostId = externalPostId;
        this.status = STATUS_PUBLISHED;
        this.publishedAt = now;
    }

    public void markFailed(LinkedInPublishingException exception) {
        this.status = exception.isOutcomeUnknown() ? STATUS_OUTCOME_UNKNOWN : STATUS_FAILED;
        this.failureCode = exception.isOutcomeUnknown()
                ? FAILURE_RESULT_UNKNOWN
                : exception.getFailureCode();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getAppUser() { return appUser; }
    public void setAppUser(AppUser appUser) { this.appUser = appUser; }
    public ContentDraft getDraft() { return draft; }
    public void setDraft(ContentDraft draft) { this.draft = draft; }
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
