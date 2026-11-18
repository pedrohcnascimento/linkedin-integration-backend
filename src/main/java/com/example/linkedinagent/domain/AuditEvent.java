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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    private static final List<String> FORBIDDEN_METADATA_MARKERS = List.of(
            "v1.",
            "bearer ",
            "access_token",
            "refresh_token",
            "client_secret",
            "password",
            "authorization_code");

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id")
    private AppUser appUser;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "resource_type", length = 128)
    private String resourceType;

    @Column(name = "resource_id", length = 255)
    private String resourceId;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "occurred_at", nullable = false, length = 64)
    private Instant occurredAt;

    @Column(name = "metadata_sanitized", length = 4000)
    private String metadataSanitized;

    public static AuditEvent record(
            AppUser appUser,
            String eventType,
            String resourceType,
            String resourceId,
            String outcome,
            Instant occurredAt,
            String metadataSanitized) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Audit eventType is required.");
        }
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("Audit outcome is required.");
        }
        Objects.requireNonNull(occurredAt, "Audit occurredAt is required.");
        validateSanitizedMetadata(metadataSanitized);

        AuditEvent event = new AuditEvent();
        event.id = UUID.randomUUID();
        event.appUser = appUser;
        event.eventType = eventType.trim();
        event.resourceType = resourceType == null || resourceType.isBlank() ? null : resourceType.trim();
        event.resourceId = resourceId == null || resourceId.isBlank() ? null : resourceId.trim();
        event.outcome = outcome.trim();
        event.occurredAt = occurredAt;
        event.metadataSanitized = metadataSanitized == null || metadataSanitized.isBlank()
                ? null
                : metadataSanitized.trim();
        return event;
    }

    private static void validateSanitizedMetadata(String metadataSanitized) {
        if (metadataSanitized == null || metadataSanitized.isBlank()) {
            return;
        }
        String normalized = metadataSanitized.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN_METADATA_MARKERS) {
            if (normalized.contains(marker)) {
                throw new IllegalArgumentException("Audit metadata contains unsanitized sensitive data.");
            }
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getAppUser() { return appUser; }
    public void setAppUser(AppUser appUser) { this.appUser = appUser; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public String getMetadataSanitized() { return metadataSanitized; }
    public void setMetadataSanitized(String metadataSanitized) { this.metadataSanitized = metadataSanitized; }
}
