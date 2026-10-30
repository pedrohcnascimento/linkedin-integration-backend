package com.example.linkedinagent.config;

import com.example.linkedinagent.security.RateLimitInterceptor;
import com.example.linkedinagent.service.RateLimitService;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;
    private final RateLimitService rateLimitService;

    public WebConfig(AppProperties appProperties, RateLimitService rateLimitService) {
        this.appProperties = appProperties;
        this.rateLimitService = rateLimitService;
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
        registry.addInterceptor(new RateLimitInterceptor(rateLimitService))
                .addPathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/linkedin/oauth/start",
                        "/api/v1/linkedin/oauth/callback",
                        "/api/v1/drafts/*/publish",
                        "/api/v1/publications",
                        "/api/v1/publications/**");
    }
}
