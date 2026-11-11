package com.example.linkedinagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class SecurityConfig {

    public static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofHours(8);

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    @Profile("local")
    CsrfTokenRepository localCsrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    @Profile("!local")
    CsrfTokenRepository csrfTokenRepository(Environment environment) {
        CookieCsrfTokenRepository cookieRepository = new CookieCsrfTokenRepository();
        boolean secureCookie = environment.acceptsProfiles(Profiles.of("prod"));
        cookieRepository.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(secureCookie).sameSite("Lax"));
        HttpSessionCsrfTokenRepository sessionRepository = new HttpSessionCsrfTokenRepository();
        return new SessionAwareCsrfTokenRepository(cookieRepository, sessionRepository);
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            Environment environment) {
        boolean allowSwagger = environment.acceptsProfiles(Profiles.of("local | test"));
        boolean prodProfile = environment.acceptsProfiles(Profiles.of("prod"));
        http
                .cors(Customizer.withDefaults())
                .headers(headers -> {
                    headers.referrerPolicy(referrer -> referrer.policy(
                                    ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                            .permissionsPolicyHeader(permissions -> permissions.policy(
                                    "geolocation=(), microphone=(), camera=()"));
                    if (prodProfile) {
                        headers.httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)
                                .requestMatcher(request -> true));
                    }
                })
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .sessionAuthenticationStrategy(sessionAuthenticationStrategy))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED))
                .addFilterAfter(new AbsoluteSessionTimeoutFilter(), SecurityContextHolderFilter.class)
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                            "/api/v1/auth/csrf",
                            "/api/v1/auth/register",
                            "/api/v1/auth/login",
                            "/actuator/health").permitAll();
                    if (allowSwagger) {
                        authorize.requestMatchers(
                                "/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                response.setStatus(HttpStatus.UNAUTHORIZED.value())));
        return http.build();
    }

    static final class SessionAwareCsrfTokenRepository implements CsrfTokenRepository {

        private static final String SESSION_ATTR = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

        private final CookieCsrfTokenRepository cookieRepository;
        private final HttpSessionCsrfTokenRepository sessionRepository;

        SessionAwareCsrfTokenRepository(
                CookieCsrfTokenRepository cookieRepository,
                HttpSessionCsrfTokenRepository sessionRepository) {
            this.cookieRepository = cookieRepository;
            this.sessionRepository = sessionRepository;
        }

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return hasAuthenticatedSession(request)
                    ? sessionRepository.generateToken(request)
                    : cookieRepository.generateToken(request);
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            if (hasAuthenticatedSession(request)) {
                sessionRepository.saveToken(token, request, response);
                if (token == null) {
                    cookieRepository.saveToken(null, request, response);
                }
            } else {
                cookieRepository.saveToken(token, request, response);
            }
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            if (hasAuthenticatedSession(request)) {
                CsrfToken sessionToken = sessionRepository.loadToken(request);
                if (sessionToken != null) {
                    return sessionToken;
                }
            }
            return cookieRepository.loadToken(request);
        }

        private static boolean hasAuthenticatedSession(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            return session != null && session.getAttribute(SESSION_ATTR) != null;
        }
    }

    static final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            HttpSession session = request.getSession(false);
            if (session != null) {
                long createdAtMillis = session.getCreationTime();
                long ageMillis = Instant.now().toEpochMilli() - createdAtMillis;
                if (ageMillis > ABSOLUTE_SESSION_LIFETIME.toMillis()) {
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
