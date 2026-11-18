package com.example.linkedinagent.service;

import com.example.linkedinagent.domain.RateLimitBucket;
import com.example.linkedinagent.exception.RateLimitExceededException;
import com.example.linkedinagent.exception.RateLimitStoreUnavailableException;
import com.example.linkedinagent.repository.RateLimitStore;
import com.example.linkedinagent.security.AesGcmTokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] hmacKey;
    private final RateLimitStore store;

    public RateLimitService(
            @Qualifier("rateLimitStore") RateLimitStore store,
            @Value("${app.rate-limit.hmac-key}") String encodedHmacKey) {
        this(store, encodedHmacKey, true);
    }

    @Autowired
    public RateLimitService(
            @Qualifier("rateLimitStore") RateLimitStore store,
            @Value("${app.rate-limit.hmac-key}") String encodedHmacKey,
            Environment environment) {
        this(store, encodedHmacKey,
                environment == null || !environment.acceptsProfiles(Profiles.of("local | test")));
    }

    RateLimitService(
            RateLimitStore store,
            String encodedHmacKey,
            boolean rejectWeakKeys) {
        this.store = store;
        try {
            this.hmacKey = Base64.getDecoder().decode(encodedHmacKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Rate limit HMAC key must be valid Base64.", exception);
        }
        if (hmacKey.length < 32) {
            throw new IllegalStateException("Rate limit HMAC key must contain at least 32 bytes.");
        }
        if (rejectWeakKeys && AesGcmTokenCipher.isWeakKey(hmacKey)) {
            throw new IllegalStateException("Rate limit HMAC key is too weak; generate at least 32 random bytes.");
        }
    }

    public void check(String scope, List<Limit> limits) {
        List<RateLimitBucket> buckets = toBuckets(scope, limits);
        try {
            store.acquire(buckets).ifPresent(retryAfter -> {
                throw new RateLimitExceededException(retryAfter);
            });
        } catch (DataAccessException exception) {
            throw new RateLimitStoreUnavailableException();
        }
    }

    public void verifyNotExceeded(String scope, List<Limit> limits) {
        List<RateLimitBucket> buckets = toBuckets(scope, limits);
        try {
            store.peek(buckets).ifPresent(retryAfter -> {
                buckets.stream()
                        .filter(bucket -> "account".equals(bucket.dimension()))
                        .findFirst()
                        .ifPresent(bucket -> log.warn(
                                "Security alert: distributed failure ceiling reached for scope={} accountHash={}",
                                scope, bucket.subjectHash()));
                throw new RateLimitExceededException(retryAfter);
            });
        } catch (DataAccessException exception) {
            throw new RateLimitStoreUnavailableException();
        }
    }

    private List<RateLimitBucket> toBuckets(String scope, List<Limit> limits) {
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
        return List.copyOf(buckets);
    }

    private String hash(String dimension, String identity) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            byte[] value = (dimension + "\0" + identity).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(mac.doFinal(value));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", exception);
        }
    }

    public record Limit(String dimension, String identity, int maximumRequests, Duration window) {
    }
}
