package com.example.linkedinagent.application.ratelimit;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class RateLimitService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] hmacKey;
    private final RateLimitStore store;

    public RateLimitService(
            RateLimitStore store,
            @Value("${app.rate-limit.hmac-key}") String encodedHmacKey) {
        this.store = store;
        try {
            this.hmacKey = Base64.getDecoder().decode(encodedHmacKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Rate limit HMAC key must be valid Base64.", exception);
        }
        if (hmacKey.length < 32) {
            throw new IllegalStateException("Rate limit HMAC key must contain at least 32 bytes.");
        }
    }

    public void check(String scope, List<Limit> limits) {
        List<RateLimitBucket> buckets = new ArrayList<>(limits.size());
        for (Limit limit : limits) {
            if (limit.identity() == null || limit.identity().isBlank()) {
                continue;
            }
            buckets.add(new RateLimitBucket(
                    scope,
                    limit.dimension(),
                    hash(limit.dimension(), limit.identity()),
                    limit.maximumRequests(),
                    limit.window()));
        }
        if (buckets.isEmpty()) {
            throw new IllegalArgumentException("At least one rate limit identity is required.");
        }

        try {
            store.acquire(List.copyOf(buckets)).ifPresent(retryAfter -> {
                throw new RateLimitExceededException(retryAfter);
            });
        } catch (DataAccessException exception) {
            throw new RateLimitStoreUnavailableException();
        }
    }

    private String hash(String dimension, String identity) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            byte[] value = (dimension + "\0" + identity).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(mac.doFinal(value));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }

    public record Limit(String dimension, String identity, int maximumRequests, Duration window) {
    }
}
