package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.RateLimitBucket;
import com.example.linkedinagent.exception.RateLimitStoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitStoreTest {

    private static final String VALID_SUBJECT_HASH = "a".repeat(64);

    @Test
    void inMemoryStoreEnforcesFixedWindowLimitsAndResetsAfterWindowExpires() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T12:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(clock);
        List<RateLimitBucket> buckets = List.of(
                new RateLimitBucket("auth-login", "ip", VALID_SUBJECT_HASH, 2, Duration.ofSeconds(60)));

        assertThat(store.acquire(buckets)).isEmpty();
        assertThat(store.acquire(buckets)).isEmpty();
        assertThat(store.acquire(buckets)).contains(Duration.ofSeconds(60));

        now.set(now.get().plusSeconds(25));
        assertThat(store.acquire(buckets)).contains(Duration.ofSeconds(35));

        now.set(now.get().plusSeconds(35));
        assertThat(store.acquire(buckets)).isEmpty();
    }

    @Test
    void redisStoreMapsScriptReturnValuesAndFailsClosedWhenRedisReturnsNull() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisRateLimitStore store = new RedisRateLimitStore(redisTemplate, "linkedin-test");
        List<RateLimitBucket> buckets = List.of(
                new RateLimitBucket("oauth-start", "account", VALID_SUBJECT_HASH, 10, Duration.ofMinutes(60)));
        List<String> expectedKeys = List.of("linkedin-test:{oauth-start}:account:" + VALID_SUBJECT_HASH);

        when(redisTemplate.execute(any(RedisScript.class), eq(expectedKeys), eq("10"), eq("3600000")))
                .thenReturn(0L)
                .thenReturn(42L)
                .thenReturn(null);

        assertThat(store.acquire(buckets)).isEmpty();
        assertThat(store.acquire(buckets)).contains(Duration.ofSeconds(42));
        assertThatThrownBy(() -> store.acquire(buckets))
                .isInstanceOf(RateLimitStoreUnavailableException.class);
    }
}
