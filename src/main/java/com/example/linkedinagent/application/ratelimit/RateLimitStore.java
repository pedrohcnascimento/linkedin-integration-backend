package com.example.linkedinagent.application.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface RateLimitStore {

    Optional<Duration> acquire(List<RateLimitBucket> buckets);
}
