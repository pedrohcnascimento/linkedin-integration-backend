package com.example.linkedinagent.domain;

import java.time.Instant;

public record LinkedInConnection(
        String memberSubject,
        String scopes,
        String status,
        Instant expiresAt,
        Instant connectedAt) {
}
