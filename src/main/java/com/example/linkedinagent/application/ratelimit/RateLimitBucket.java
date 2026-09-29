package com.example.linkedinagent.application.ratelimit;

import java.time.Duration;

public record RateLimitBucket(
        String scope,
        String dimension,
        String subjectHash,
        int maximumRequests,
        Duration window) {

    public RateLimitBucket {
        if (scope == null || !scope.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Rate limit scope must be a lowercase identifier.");
        }
        if (dimension == null || !dimension.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Rate limit dimension must be a lowercase identifier.");
        }
        if (subjectHash == null || !subjectHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Rate limit subject must be a SHA-256 HMAC.");
        }
        if (maximumRequests < 1) {
            throw new IllegalArgumentException("Rate limit must allow at least one request.");
        }
        if (window == null || window.isZero() || window.isNegative() || window.toMillis() < 1) {
            throw new IllegalArgumentException("Rate limit window must be at least one millisecond.");
        }
    }

    public String storageKey() {
        return scope + ":" + dimension + ":" + subjectHash;
    }
}
