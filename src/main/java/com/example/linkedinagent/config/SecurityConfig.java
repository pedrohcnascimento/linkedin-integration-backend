package com.example.linkedinagent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class SecurityConfig {

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
            Environment environment) {
        boolean allowSwagger = environment.acceptsProfiles(Profiles.of("local | test"));
        http
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "geolocation=(), microphone=(), camera=()")))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                            "/api/v1/auth/csrf",
                            "/api/v1/auth/register",
                            "/api/v1/auth/login",
                            "/api/v1/linkedin/oauth/callback",
                            "/actuator/health").permitAll();
                    if (allowSwagger) {
                        authorize.requestMatchers(
                                "/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
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
            return request.getSession(false) != null
                    ? sessionRepository.generateToken(request)
                    : cookieRepository.generateToken(request);
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            if (request.getSession(false) != null) {
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
            if (request.getSession(false) != null) {
                CsrfToken sessionToken = sessionRepository.loadToken(request);
                if (sessionToken != null) {
                    return sessionToken;
                }
            }
            return cookieRepository.loadToken(request);
        }
    }
}
