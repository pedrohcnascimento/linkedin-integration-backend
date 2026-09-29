package com.example.linkedinagent.application.ratelimit;

public class RateLimitStoreUnavailableException extends RuntimeException {

    public RateLimitStoreUnavailableException() {
        super("The rate limit store is unavailable.");
    }
}
