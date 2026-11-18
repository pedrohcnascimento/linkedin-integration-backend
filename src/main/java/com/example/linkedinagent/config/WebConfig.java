package com.example.linkedinagent.config;

import com.example.linkedinagent.security.RateLimitInterceptor;
import com.example.linkedinagent.service.RateLimitService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final AppProperties appProperties;
    private final RateLimitService rateLimitService;
    private final Environment environment;

    public WebConfig(AppProperties appProperties, RateLimitService rateLimitService) {
        this(appProperties, rateLimitService, null);
    }

    @Autowired
    public WebConfig(AppProperties appProperties, RateLimitService rateLimitService, Environment environment) {
        this.appProperties = appProperties;
        this.rateLimitService = rateLimitService;
        this.environment = environment;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        List<String> origins = parseAllowedOrigins(appProperties.getSecurity().getCorsAllowedOrigins());

        if (!origins.isEmpty()) {
            registry.addMapping("/api/**")
                    .allowedOrigins(origins.toArray(String[]::new))
                    .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
        }
    }

    static List<String> parseAllowedOrigins(String configuredOrigins) {
        if (!StringUtils.hasText(configuredOrigins)) {
            return List.of();
        }

        List<String> origins = Arrays.stream(configuredOrigins.split(",", -1))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        for (String origin : origins) {
            if (!isValidOrigin(origin)) {
                throw new IllegalArgumentException("CORS_ALLOWED_ORIGINS contains an invalid origin.");
            }
        }
        return origins;
    }

    public static Set<String> parseTrustedProxyIps(String configuredProxyIps) {
        if (!StringUtils.hasText(configuredProxyIps)) {
            return Set.of();
        }
        Set<String> specs = Arrays.stream(configuredProxyIps.split(",", -1))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
        for (String spec : specs) {
            try {
                new IpAddressMatcher(spec);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("TRUSTED_PROXY_IPS contains an invalid IP or CIDR: " + spec, exception);
            }
        }
        return specs;
    }

    private static boolean isValidOrigin(String origin) {
        if ("*".equals(origin)) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getPort() == -1 || (uri.getPort() >= 0 && uri.getPort() <= 65535));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        Set<String> trustedProxyIps = parseTrustedProxyIps(appProperties.getSecurity().getTrustedProxyIps());
        if (environment != null && environment.acceptsProfiles(Profiles.of("prod")) && trustedProxyIps.isEmpty()) {
            log.warn("TRUSTED_PROXY_IPS is empty in prod profile; X-Forwarded-For headers will be ignored and rate limiting will use request.getRemoteAddr() directly. Configure TRUSTED_PROXY_IPS (IPs or CIDRs) when deploying behind a reverse proxy or load balancer.");
        }
        registry.addInterceptor(new RateLimitInterceptor(rateLimitService, trustedProxyIps))
                .addPathPatterns(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/users/me",
                        "/api/v1/linkedin/connection",
                        "/api/v1/linkedin/oauth/start",
                        "/api/v1/linkedin/oauth/callback",
                        "/api/v1/drafts",
                        "/api/v1/drafts/**",
                        "/api/v1/publications",
                        "/api/v1/publications/**");
    }
}
