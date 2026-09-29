package com.example.linkedinagent.config;

import com.example.linkedinagent.adapter.in.web.RateLimitInterceptor;
import com.example.linkedinagent.application.ratelimit.RateLimitService;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
        String allowedOrigins = appProperties.getSecurity().getCorsAllowedOrigins();

        if (StringUtils.hasText(allowedOrigins)) {
            String[] origins = allowedOrigins.split(",");
            registry.addMapping("/api/**")
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true);
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
                        "/api/v1/publications",
                        "/api/v1/publications/**");
    }
}
