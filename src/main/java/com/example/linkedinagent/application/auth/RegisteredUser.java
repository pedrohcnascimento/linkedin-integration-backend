package com.example.linkedinagent.application.auth;

import java.time.Instant;
import java.util.UUID;

public record RegisteredUser(UUID id, String email, String displayName, Instant createdAt) {
}
