package com.example.linkedinagent.domain.publishing;

public record LinkedInPublicationCommand(
        String memberSubject,
        String commentary,
        Visibility visibility) {

    public LinkedInPublicationCommand {
        if (memberSubject == null || memberSubject.isBlank()) {
            throw new IllegalArgumentException("memberSubject is required");
        }
        if (commentary == null || commentary.isBlank()) {
            throw new IllegalArgumentException("commentary is required");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("visibility is required");
        }
    }

    public enum Visibility {
        PUBLIC,
        CONNECTIONS
    }
}
