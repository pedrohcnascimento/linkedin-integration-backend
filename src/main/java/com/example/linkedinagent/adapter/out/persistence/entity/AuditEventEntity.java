package com.example.linkedinagent.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEventEntity {
    @Id
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id")
    private AppUserEntity appUser;

    @Column(name = "event_type", nullable = false, columnDefinition = "TEXT")
    private String eventType;

    @Column(name = "resource_type", columnDefinition = "TEXT")
    private String resourceType;

    @Column(name = "resource_id", columnDefinition = "TEXT")
    private String resourceId;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "TEXT")
    private Instant occurredAt;

    @Column(name = "metadata_sanitized", columnDefinition = "TEXT")
    private String metadataSanitized;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUserEntity getAppUser() { return appUser; }
    public void setAppUser(AppUserEntity appUser) { this.appUser = appUser; }
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
