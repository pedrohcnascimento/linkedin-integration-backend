package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.RateLimitBucket;
import com.example.linkedinagent.exception.RateLimitStoreUnavailableException;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryRateLimitStore implements RateLimitStore {

    private static final int MAX_BUCKETS = 50_000;
    private final Clock clock;
    private final Map<String, Counter> counters = new HashMap<>();
    private int callsSinceCleanup;

    public InMemoryRateLimitStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Optional<Duration> acquire(List<RateLimitBucket> buckets) {
        long now = clock.millis();
        cleanupExpired(now);
        Optional<Duration> blocked = findRetryAfter(buckets, now);
        if (blocked.isPresent()) {
            return blocked;
        }

        long newBuckets = buckets.stream().map(RateLimitBucket::storageKey).distinct()
                .filter(key -> !counters.containsKey(key)).count();
        if (counters.size() + newBuckets > MAX_BUCKETS) {
            throw new RateLimitStoreUnavailableException();
        }
        for (RateLimitBucket bucket : buckets) {
            String key = bucket.storageKey();
            Counter counter = counters.get(key);
            if (counter == null || now >= counter.resetAtMillis()) {
                counters.put(key, new Counter(1, now + bucket.window().toMillis()));
            } else {
                counters.put(key, new Counter(counter.count() + 1, counter.resetAtMillis()));
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<Duration> peek(List<RateLimitBucket> buckets) {
        long now = clock.millis();
        cleanupExpired(now);
        return findRetryAfter(buckets, now);
    }

    private Optional<Duration> findRetryAfter(List<RateLimitBucket> buckets, long now) {
        long retryMillis = 0;
        for (RateLimitBucket bucket : buckets) {
            Counter counter = counters.get(bucket.storageKey());
            if (counter == null || now >= counter.resetAtMillis()) {
                continue;
            }
            if (counter.count() >= bucket.maximumRequests()) {
                retryMillis = Math.max(retryMillis, counter.resetAtMillis() - now);
            }
        }
        return retryMillis > 0 ? Optional.of(Duration.ofMillis(retryMillis)) : Optional.empty();
    }

    private void cleanupExpired(long now) {
        if (++callsSinceCleanup < 256) {
            return;
        }
        callsSinceCleanup = 0;
        Iterator<Counter> countersIterator = counters.values().iterator();
        while (countersIterator.hasNext()) {
            if (now >= countersIterator.next().resetAtMillis()) {
                countersIterator.remove();
            }
        }
    }

    private record Counter(int count, long resetAtMillis) {
    }
}
