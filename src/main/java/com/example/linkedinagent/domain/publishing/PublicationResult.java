package com.example.linkedinagent.domain.publishing;

public record PublicationResult(String externalPostId) {
    public PublicationResult {
        if (externalPostId == null || externalPostId.isBlank()) {
            throw new IllegalArgumentException("externalPostId is required");
        }
    }
}
