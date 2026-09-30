package com.example.linkedinagent.config;

import com.example.linkedinagent.adapter.out.ratelimit.InMemoryRateLimitStore;
import com.example.linkedinagent.adapter.out.ratelimit.RedisRateLimitStore;
import com.example.linkedinagent.application.ratelimit.RateLimitStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

@Configuration
public class RateLimitConfiguration {

    @Bean(name = "rateLimitStore")
    @Profile("!prod")
    RateLimitStore inMemoryRateLimitStore(Clock clock) {
        return new InMemoryRateLimitStore(clock);
    }

    @Bean(name = "rateLimitStore")
    @Profile("prod")
    RateLimitStore redisRateLimitStore(
            StringRedisTemplate redisTemplate,
            @Value("${app.rate-limit.redis-key-prefix:linkedin-integration}") String keyPrefix) {
        return new RedisRateLimitStore(redisTemplate, keyPrefix);
    }
}
