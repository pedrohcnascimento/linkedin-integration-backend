package com.example.linkedinagent.adapter.out.ratelimit;

import com.example.linkedinagent.application.ratelimit.RateLimitBucket;
import com.example.linkedinagent.application.ratelimit.RateLimitStore;
import com.example.linkedinagent.application.ratelimit.RateLimitStoreUnavailableException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RedisRateLimitStore implements RateLimitStore {

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local retryAfterMillis = 0
            for i, key in ipairs(KEYS) do
                local count = tonumber(redis.call('GET', key) or '0')
                local limit = tonumber(ARGV[(i - 1) * 2 + 1])
                local windowMillis = tonumber(ARGV[(i - 1) * 2 + 2])
                if count >= limit then
                    local ttl = redis.call('PTTL', key)
                    if ttl < 1 then
                        ttl = windowMillis
                    end
                    if ttl > retryAfterMillis then
                        retryAfterMillis = ttl
                    end
                end
            end
            if retryAfterMillis > 0 then
                return math.ceil(retryAfterMillis / 1000)
            end
            for i, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 then
                    redis.call('PEXPIRE', key, tonumber(ARGV[(i - 1) * 2 + 2]))
                end
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisRateLimitStore(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<Duration> acquire(List<RateLimitBucket> buckets) {
        List<String> keys = new ArrayList<>(buckets.size());
        List<String> arguments = new ArrayList<>(buckets.size() * 2);
        for (RateLimitBucket bucket : buckets) {
            keys.add(keyPrefix + ":{" + bucket.scope() + "}:" + bucket.dimension() + ":" + bucket.subjectHash());
            arguments.add(Integer.toString(bucket.maximumRequests()));
            arguments.add(Long.toString(bucket.window().toMillis()));
        }

        Long retryAfterSeconds = redisTemplate.execute(ACQUIRE_SCRIPT, keys, arguments.toArray());
        if (retryAfterSeconds == null) {
            throw new RateLimitStoreUnavailableException();
        }
        return retryAfterSeconds > 0
                ? Optional.of(Duration.ofSeconds(retryAfterSeconds))
                : Optional.empty();
    }
}
