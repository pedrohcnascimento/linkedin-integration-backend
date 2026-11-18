package com.example.linkedinagent.exception;

public class RateLimitStoreUnavailableException extends RuntimeException {

    public RateLimitStoreUnavailableException() {
        super("The rate limit store is unavailable.");
    }
}
