package com.example.linkedinagent.adapter.in.web;

import com.example.linkedinagent.application.ratelimit.RateLimitService;
import com.example.linkedinagent.adapter.out.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Map<String, IpPolicy> POLICIES = Map.of(
            "POST /api/v1/auth/login", new IpPolicy("auth-login", 30, FIFTEEN_MINUTES),
            "POST /api/v1/auth/register", new IpPolicy("auth-register", 10, ONE_HOUR),
            "GET /api/v1/linkedin/oauth/start", new IpPolicy("oauth-start", 30, FIFTEEN_MINUTES),
            "GET /api/v1/linkedin/oauth/callback", new IpPolicy("oauth-callback", 60, FIFTEEN_MINUTES));

    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        String path = request.getServletPath();
        String route = request.getMethod() + " " + path;
        IpPolicy policy = POLICIES.get(route);
        if (policy != null) {
            rateLimitService.check(policy.scope(), List.of(
                    new RateLimitService.Limit("ip", clientAddress(request),
                            policy.maximumRequests(), policy.window())));
            return true;
        }
        boolean publicationWrite = (path.equals("/api/v1/publications")
                || path.startsWith("/api/v1/publications/")
                || path.matches("/api/v1/drafts/[^/]+/publish"))
                && List.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod());
        if (publicationWrite
        ) {
            List<RateLimitService.Limit> limits = new ArrayList<>();
            limits.add(new RateLimitService.Limit("ip", clientAddress(request), 30, FIFTEEN_MINUTES));
            if (request.getUserPrincipal() instanceof Authentication authentication
                    && authentication.getPrincipal() instanceof AppUserPrincipal user) {
                limits.add(new RateLimitService.Limit(
                        "account", user.getId().toString(), 10, FIFTEEN_MINUTES));
                if (request.getSession(false) != null) {
                    limits.add(new RateLimitService.Limit(
                            "session", request.getSession(false).getId(), 10, FIFTEEN_MINUTES));
                }
            }
            rateLimitService.check("publication-write", limits);
        }
        return true;
    }

    private String clientAddress(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private record IpPolicy(String scope, int maximumRequests, Duration window) {
    }
}
