package com.example.linkedinagent.domain;

import com.example.linkedinagent.exception.DraftWorkflowException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "content_draft")
public class ContentDraft {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String MEDIA_NONE = "NONE";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Column(name = "media_category", columnDefinition = "TEXT")
    private String mediaCategory;

    @Column(name = "original_url", columnDefinition = "TEXT")
    private String originalUrl;

    @Column
    private String title;

    @Column(nullable = false)
    private String status;

    @Column(name = "approved_at", columnDefinition = "TEXT")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    private Instant updatedAt;

    public static ContentDraft createTextDraft(
            AppUser owner,
            String text,
            String mediaCategory,
            String originalUrl,
            String title,
            Instant now) {
        if (text == null || text.isBlank()) {
            throw new DraftWorkflowException("INVALID_DRAFT", "Draft text is required.", HttpStatus.BAD_REQUEST);
        }
        validateTextOnlyMedia(mediaCategory, originalUrl);

        ContentDraft draft = new ContentDraft();
        draft.setId(UUID.randomUUID());
        draft.setAppUser(owner);
        draft.setText(text.trim());
        draft.setMediaCategory(MEDIA_NONE);
        draft.setOriginalUrl(null);
        draft.setTitle(title == null || title.isBlank() ? null : title.trim());
        draft.setStatus(STATUS_DRAFT);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        return draft;
    }

    public void applyUpdate(String text, String mediaCategory, String originalUrl, String title, Instant now) {
        if (!STATUS_DRAFT.equals(this.status)) {
            throw new DraftWorkflowException("DRAFT_INVALID_STATE", "Only a DRAFT can be edited.", HttpStatus.CONFLICT);
        }
        if (text == null && mediaCategory == null && originalUrl == null && title == null) {
            throw new DraftWorkflowException(
                    "INVALID_DRAFT_UPDATE", "At least one draft field must be provided.", HttpStatus.BAD_REQUEST);
        }
        if (text != null) {
            if (text.isBlank()) {
                throw new DraftWorkflowException("INVALID_DRAFT", "Draft text cannot be blank.", HttpStatus.BAD_REQUEST);
            }
            this.text = text.trim();
        }
        if (mediaCategory != null) {
            if (!mediaCategory.isBlank() && !MEDIA_NONE.equals(mediaCategory)) {
                throw new DraftWorkflowException(
                        "DRAFT_MEDIA_UNSUPPORTED",
                        "Only text drafts are supported in this release.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            this.mediaCategory = MEDIA_NONE;
        }
        if (originalUrl != null) {
            if (!originalUrl.isBlank()) {
                throw new DraftWorkflowException(
                        "DRAFT_MEDIA_UNSUPPORTED",
                        "Article content is not supported in this release.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            this.originalUrl = null;
        }
        if (title != null) {
            this.title = title.isBlank() ? null : title.trim();
        }
        this.updatedAt = now;
    }

    public void approve(Instant now) {
        if (!STATUS_DRAFT.equals(this.status)) {
            throw new DraftWorkflowException("DRAFT_INVALID_STATE", "Only a DRAFT can be approved.", HttpStatus.CONFLICT);
        }
        this.status = STATUS_APPROVED;
        this.approvedAt = now;
        this.updatedAt = now;
    }

    public void ensureApprovedForPublishing() {
        if (!STATUS_APPROVED.equals(this.status)) {
            throw new DraftWorkflowException(
                    "DRAFT_NOT_APPROVED", "Only an APPROVED draft can be published.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public void markPublished(Instant now) {
        this.status = STATUS_PUBLISHED;
        this.updatedAt = now;
    }

    private static void validateTextOnlyMedia(String mediaCategory, String originalUrl) {
        if (mediaCategory != null && !mediaCategory.isBlank() && !MEDIA_NONE.equals(mediaCategory)) {
            throw new DraftWorkflowException(
                    "DRAFT_MEDIA_UNSUPPORTED",
                    "Only text drafts are supported in this release.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (originalUrl != null && !originalUrl.isBlank()) {
            throw new DraftWorkflowException(
                    "DRAFT_MEDIA_UNSUPPORTED",
                    "Article content is not supported in this release.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getAppUser() { return appUser; }
    public void setAppUser(AppUser appUser) { this.appUser = appUser; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getMediaCategory() { return mediaCategory; }
    public void setMediaCategory(String mediaCategory) { this.mediaCategory = mediaCategory; }
    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
