package com.example.linkedinagent.security;

import com.example.linkedinagent.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitInterceptorTest {

    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimitService);

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "GET,    /api/v1/auth/csrf,                      auth-csrf",
            "POST,   /api/v1/auth/login,                     auth-login",
            "POST,   /api/v1/auth/register,                  auth-register",
            "GET,    /api/v1/linkedin/oauth/start,           oauth-start",
            "GET,    /api/v1/linkedin/oauth/callback,        oauth-callback",
            "POST,   /api/v1/drafts/abc/publish,             publication-write",
            "POST,   /api/v1/publications,                   publication-write",
            "DELETE, /api/v1/publications/abc,               publication-write",
            "POST,   /api/v1/drafts,                         draft-write",
            "PATCH,  /api/v1/drafts/abc,                     draft-write",
            "POST,   /api/v1/drafts/abc/approve,             draft-write",
            "PUT,    /api/v1/drafts/abc/schedule,            draft-write",
            "DELETE, /api/v1/drafts/abc/schedule,            draft-write",
            "GET,    /api/v1/drafts,                         api-read",
            "GET,    /api/v1/drafts/abc,                     api-read",
            "GET,    /api/v1/publications,                   api-read",
            "GET,    /api/v1/users/me,                       api-read",
            "GET,    /api/v1/linkedin/connection,            api-read",
            "OPTIONS,/api/v1/drafts,                         api-read",
            // Unclassified writes must never fall into the permissive read bucket.
            "DELETE, /api/v1/drafts/abc,                     api-write-default",
            "PUT,    /api/v1/drafts/abc,                     api-write-default",
            "DELETE, /api/v1/users/me,                       api-write-default",
            "DELETE, /api/v1/linkedin/connection,            api-write-default",
            "POST,   /api/v1/drafts/abc/unknown-action,      api-write-default"
    })
    void everyInterceptedRequestIsRateLimitedWithTheExpectedScope(String method, String path, String expectedScope) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr("203.0.113.10");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        verify(rateLimitService, times(1)).check(eq(expectedScope), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unclassifiedWriteUsesTheMostRestrictiveIpLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/drafts/abc");
        request.setServletPath("/api/v1/drafts/abc");
        request.setRemoteAddr("203.0.113.10");
        ArgumentCaptor<List<RateLimitService.Limit>> limits = ArgumentCaptor.forClass(List.class);

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(rateLimitService).check(eq("api-write-default"), limits.capture());
        assertThat(limits.getValue())
                .singleElement()
                .satisfies(limit -> {
                    assertThat(limit.dimension()).isEqualTo("ip");
                    assertThat(limit.identity()).isEqualTo("203.0.113.10");
                    assertThat(limit.maximumRequests()).isEqualTo(30);
                });
    }
}
