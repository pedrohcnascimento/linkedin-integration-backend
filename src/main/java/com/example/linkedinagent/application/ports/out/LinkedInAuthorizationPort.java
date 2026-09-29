package com.example.linkedinagent.application.ports.out;

import com.example.linkedinagent.application.linkedin.LinkedInConnection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LinkedInAuthorizationPort {

    void save(
            UUID appUserId,
            String memberSubject,
            String encryptedAccessToken,
            String encryptedRefreshToken,
            String scopes,
            Instant expiresAt,
            Instant now);

    Optional<LinkedInConnection> findByAppUserId(UUID appUserId);

    void deleteByAppUserId(UUID appUserId);
}
