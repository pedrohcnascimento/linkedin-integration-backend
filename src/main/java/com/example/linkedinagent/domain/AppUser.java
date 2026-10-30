package com.example.linkedinagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    public static final String STATUS_ACTIVE = "ACTIVE";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", columnDefinition = "TEXT")
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false, columnDefinition = "TEXT")
    private String displayName;

    @Column(name = "password_hash", nullable = false, columnDefinition = "TEXT")
    private String passwordHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    private Instant updatedAt;

    public static AppUser createNew(String normalizedEmail, String displayName, String passwordHash, Instant now) {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEmail(normalizedEmail);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordHash);
        user.setStatus(STATUS_ACTIVE);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    public static AppUser reference(UUID id) {
        AppUser user = new AppUser();
        user.setId(id);
        return user;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
