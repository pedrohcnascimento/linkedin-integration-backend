package com.example.linkedinagent.security;

import com.example.linkedinagent.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Map<String, IpPolicy> POLICIES = Map.of(
            "GET /api/v1/auth/csrf", new IpPolicy("auth-csrf", 30, FIFTEEN_MINUTES),
            "POST /api/v1/auth/login", new IpPolicy("auth-login", 30, FIFTEEN_MINUTES),
            "POST /api/v1/auth/register", new IpPolicy("auth-register", 10, ONE_HOUR),
            "GET /api/v1/linkedin/oauth/start", new IpPolicy("oauth-start", 30, FIFTEEN_MINUTES),
            "GET /api/v1/linkedin/oauth/callback", new IpPolicy("oauth-callback", 60, FIFTEEN_MINUTES));

    private final RateLimitService rateLimitService;
    private final Set<String> trustedProxyIps;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this(rateLimitService, Set.of());
    }

    public RateLimitInterceptor(RateLimitService rateLimitService, Set<String> trustedProxyIps) {
        this.rateLimitService = rateLimitService;
        this.trustedProxyIps = trustedProxyIps == null ? Set.of() : Set.copyOf(trustedProxyIps);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        String path = applicationPath(request);
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
        if (publicationWrite) {
            rateLimitService.check("publication-write", authenticatedWriteLimits(request, 30, 10));
            return true;
        }
        boolean draftWrite = ("POST".equals(request.getMethod()) && path.equals("/api/v1/drafts"))
                || ("PATCH".equals(request.getMethod()) && path.matches("/api/v1/drafts/[^/]+"))
                || ("POST".equals(request.getMethod()) && path.matches("/api/v1/drafts/[^/]+/approve"));
        if (draftWrite) {
            rateLimitService.check("draft-write", authenticatedWriteLimits(request, 60, 30));
            return true;
        }
        boolean authenticatedRead = "GET".equals(request.getMethod())
                && (path.equals("/api/v1/drafts")
                || path.equals("/api/v1/publications")
                || path.equals("/api/v1/users/me")
                || path.equals("/api/v1/linkedin/connection"));
        if (authenticatedRead) {
            rateLimitService.check("api-read", authenticatedWriteLimits(request, 240, 120));
        }
        return true;
    }

    private List<RateLimitService.Limit> authenticatedWriteLimits(
            HttpServletRequest request,
            int ipMaximumRequests,
            int accountAndSessionMaximumRequests) {
        List<RateLimitService.Limit> limits = new ArrayList<>();
        limits.add(new RateLimitService.Limit("ip", clientAddress(request), ipMaximumRequests, FIFTEEN_MINUTES));
        if (request.getUserPrincipal() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AppUserPrincipal user) {
            limits.add(new RateLimitService.Limit(
                    "account", user.getId().toString(), accountAndSessionMaximumRequests, FIFTEEN_MINUTES));
            if (request.getSession(false) != null) {
                limits.add(new RateLimitService.Limit(
                        "session",
                        request.getSession(false).getId(),
                        accountAndSessionMaximumRequests,
                        FIFTEEN_MINUTES));
            }
        }
        return limits;
    }

    private String applicationPath(HttpServletRequest request) {
        if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
            return ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication().value();
        }
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            return servletPath == null ? "" : servletPath;
        }
        return (servletPath == null ? "" : servletPath) + pathInfo;
    }

    private String clientAddress(HttpServletRequest request) {
        return resolveClientAddress(request, trustedProxyIps);
    }

    public static String resolveClientAddress(HttpServletRequest request, Set<String> trustedProxyIps) {
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "unknown";
        }
        String trimmedRemote = remoteAddress.trim();
        if (!isTrustedProxy(trimmedRemote, trustedProxyIps)) {
            return trimmedRemote;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return trimmedRemote;
        }
        String[] hops = forwardedFor.split(",", -1);
        for (int index = hops.length - 1; index >= 0; index--) {
            String candidate = hops[index].trim();
            if (!candidate.isEmpty() && !isTrustedProxy(candidate, trustedProxyIps)) {
                return candidate;
            }
        }
        return trimmedRemote;
    }

    static boolean isTrustedProxy(String ipAddress, Set<String> trustedProxySpecs) {
        if (ipAddress == null || ipAddress.isBlank() || trustedProxySpecs == null || trustedProxySpecs.isEmpty()) {
            return false;
        }
        if (trustedProxySpecs.contains(ipAddress)) {
            return true;
        }
        for (String spec : trustedProxySpecs) {
            try {
                if (new IpAddressMatcher(spec).matches(ipAddress)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Non-IP candidate or malformed address in header is never treated as a trusted proxy.
            }
        }
        return false;
    }

    private record IpPolicy(String scope, int maximumRequests, Duration window) {
    }
}
