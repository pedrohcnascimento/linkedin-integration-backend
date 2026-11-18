package com.example.linkedinagent.exception;

public class LinkedInPublishingException extends RuntimeException {
    private final String failureCode;
    private final boolean outcomeUnknown;

    public LinkedInPublishingException(String failureCode, String message) {
        this(failureCode, message, false);
    }

    public LinkedInPublishingException(String failureCode, String message, boolean outcomeUnknown) {
        super(message);
        this.failureCode = failureCode;
        this.outcomeUnknown = outcomeUnknown;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
