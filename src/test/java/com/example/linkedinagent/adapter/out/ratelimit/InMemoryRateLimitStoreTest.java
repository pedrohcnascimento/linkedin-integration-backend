package com.example.linkedinagent.adapter.out.ratelimit;

import com.example.linkedinagent.application.ratelimit.RateLimitBucket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimitStoreTest {

    @Test
    void enforcesLimitAndAllowsRequestsAfterWindowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(clock);
        RateLimitBucket bucket = bucket("client", 2, Duration.ofMinutes(1));

        assertThat(store.acquire(List.of(bucket))).isEmpty();
        assertThat(store.acquire(List.of(bucket))).isEmpty();
        assertThat(store.acquire(List.of(bucket))).contains(Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(1));
        assertThat(store.acquire(List.of(bucket))).isEmpty();
    }

    @Test
    void doesNotConsumeOtherDimensionsWhenOneDimensionIsLimited() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(Clock.systemUTC());
        RateLimitBucket account = bucket("account", 1, Duration.ofMinutes(1));
        RateLimitBucket firstIp = bucket("ip-one", 1, Duration.ofMinutes(1));
        RateLimitBucket secondIp = bucket("ip-two", 1, Duration.ofMinutes(1));

        assertThat(store.acquire(List.of(account, firstIp))).isEmpty();
        assertThat(store.acquire(List.of(account, secondIp))).isPresent();
        assertThat(store.acquire(List.of(bucket("account-two", 1, Duration.ofMinutes(1)), secondIp)))
                .isEmpty();
    }

    @Test
    void enforcesConcurrentRequestsAtomically() throws Exception {
        int allowedLimit = 7;
        int requestCount = 64;
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(Clock.systemUTC());
        RateLimitBucket bucket = bucket("shared-client", allowedLimit, Duration.ofMinutes(1));
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
                if (result.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(allowedLimit);
        }
    }

    private RateLimitBucket bucket(String identity, int maximumRequests, Duration window) {
        String hash = String.format("%064x", identity.hashCode() & 0xffffffffL);
        return new RateLimitBucket("test-route", "account", hash, maximumRequests, window);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initialInstant) {
            current = new AtomicReference<>(initialInstant);
        }

        private void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("Test clock always uses UTC.");
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
