package com.example.linkedinagent.service;

import com.example.linkedinagent.domain.RateLimitBucket;
import com.example.linkedinagent.exception.RateLimitExceededException;
import com.example.linkedinagent.exception.RateLimitStoreUnavailableException;
import com.example.linkedinagent.repository.RateLimitStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    private static final String VALID_HMAC_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void hashesIdentitiesWithHmacAndNeverStoresPlaintextSubjects() {
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.acquire(anyList())).thenReturn(Optional.empty());
        RateLimitService service = new RateLimitService(store, VALID_HMAC_KEY);

        service.check("auth-login", List.of(
                new RateLimitService.Limit("ip", "192.0.2.10", 30, Duration.ofMinutes(15)),
                new RateLimitService.Limit("account", "person@example.com", 8, Duration.ofMinutes(15)),
                new RateLimitService.Limit("session", "   ", 8, Duration.ofMinutes(15))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RateLimitBucket>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).acquire(captor.capture());
        List<RateLimitBucket> buckets = captor.getValue();

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).subjectHash())
                .hasSize(64)
                .doesNotContain("192.0.2.10");
        assertThat(buckets.get(1).subjectHash())
                .hasSize(64)
                .doesNotContain("person@example.com");
    }

    @Test
    void validatesHmacKeyAndTranslatesLimitExceededAndStoreFailures() {
        RateLimitStore store = mock(RateLimitStore.class);

        assertThatThrownBy(() -> new RateLimitService(store, "***invalid-base64***"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rate limit HMAC key must be valid Base64.");

        String shortKey = Base64.getEncoder().encodeToString("short-key".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new RateLimitService(store, shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Rate limit HMAC key must contain at least 32 bytes.");

        RateLimitService service = new RateLimitService(store, VALID_HMAC_KEY);
        assertThatThrownBy(() -> service.check("auth-login", List.of(
                new RateLimitService.Limit("ip", " ", 10, Duration.ofMinutes(1)))))
                .isInstanceOf(IllegalArgumentException.class);

        when(store.acquire(anyList())).thenReturn(Optional.of(Duration.ofSeconds(25)));
        assertThatThrownBy(() -> service.check("auth-login", List.of(
                new RateLimitService.Limit("ip", "192.0.2.10", 10, Duration.ofMinutes(1)))))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(25)));

        when(store.acquire(anyList())).thenThrow(new DataAccessResourceFailureException("redis down"));
        assertThatThrownBy(() -> service.check("auth-login", List.of(
                new RateLimitService.Limit("ip", "192.0.2.10", 10, Duration.ofMinutes(1)))))
                .isInstanceOf(RateLimitStoreUnavailableException.class);
    }
}
