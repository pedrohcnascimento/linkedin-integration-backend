package com.example.linkedinagent.adapter.out.ratelimit;

import com.example.linkedinagent.application.ratelimit.RateLimitBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RATE_LIMIT_REDIS_TEST_URL", matches = "^rediss?://.+")
class RedisRateLimitStoreIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> requiredEnvironmentVariable("RATE_LIMIT_REDIS_TEST_URL"));
    }

    @Test
    void enforcesConcurrentRequestsAtomicallyAcrossRedisClients() throws Exception {
        int allowedLimit = 6;
        int requestCount = 48;
        RedisRateLimitStore store = new RedisRateLimitStore(redisTemplate, "rate-limit-integration");
        RateLimitBucket bucket = bucket(UUID.randomUUID().toString(), allowedLimit, Duration.ofMinutes(1));
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(requestCount)) {
            var results = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return store.acquire(List.of(bucket)).isEmpty();
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long accepted = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(allowedLimit);
        }
    }

    @Test
    void allowsRequestsAgainAfterRedisWindowExpires() throws Exception {
        RedisRateLimitStore store = new RedisRateLimitStore(redisTemplate, "rate-limit-integration");
        RateLimitBucket bucket = bucket(UUID.randomUUID().toString(), 1, Duration.ofMillis(250));

        assertThat(store.acquire(List.of(bucket))).isEmpty();
        assertThat(store.acquire(List.of(bucket))).contains(Duration.ofSeconds(1));
        Thread.sleep(350);
        assertThat(store.acquire(List.of(bucket))).isEmpty();
    }

    private RateLimitBucket bucket(String subject, int maximumRequests, Duration window) {
        String hash = java.util.HexFormat.of().formatHex(
                subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hash = (hash + "0".repeat(64)).substring(0, 64);
        return new RateLimitBucket("redis-test-" + UUID.randomUUID(), "account", hash, maximumRequests, window);
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + name);
        }
        return value;
    }
}
