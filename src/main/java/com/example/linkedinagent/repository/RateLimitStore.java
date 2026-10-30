package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.RateLimitBucket;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface RateLimitStore {

    Optional<Duration> acquire(List<RateLimitBucket> buckets);
}
