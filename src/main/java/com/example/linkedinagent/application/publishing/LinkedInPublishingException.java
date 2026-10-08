package com.example.linkedinagent.application.publishing;

public class LinkedInPublishingException extends RuntimeException {
    private final String failureCode;

    public LinkedInPublishingException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
