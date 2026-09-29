package com.example.linkedinagent.adapter.out.security;

import com.example.linkedinagent.adapter.out.persistence.entity.AppUserEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

public class AppUserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String displayName;
    private final String passwordHash;
    private final Instant createdAt;
    private final boolean enabled;

    public AppUserPrincipal(AppUserEntity user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.displayName = user.getDisplayName();
        this.passwordHash = user.getPasswordHash();
        this.createdAt = user.getCreatedAt();
        this.enabled = "ACTIVE".equals(user.getStatus());
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
