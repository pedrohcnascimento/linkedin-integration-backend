package com.example.linkedinagent.config;

import com.example.linkedinagent.security.RateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WebConfigTest {

    @Test
    void trimsOriginsAndIgnoresEmptyEntries() {
        assertThat(WebConfig.parseAllowedOrigins("https://app.example.com, https://admin.example.com, "))
                .containsExactly("https://app.example.com", "https://admin.example.com");
    }

    @Test
    void emptyConfigurationDoesNotEnableCors() {
        assertThat(WebConfig.parseAllowedOrigins(" , ")).isEqualTo(List.of());
    }

    @Test
    void rejectsWildcardAndNonOriginUrls() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WebConfig.parseAllowedOrigins("*"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> WebConfig.parseAllowedOrigins("https://app.example.com/private"));
    }

    @Test
    void resolvesClientIpFromXForwardedForOnlyWhenRemoteAddrIsConfiguredAsTrustedProxy() {
        Set<String> trustedProxies = WebConfig.parseTrustedProxyIps("10.0.0.1, 10.0.0.2 ");

        MockHttpServletRequest untrustedDirectRequest = new MockHttpServletRequest();
        untrustedDirectRequest.setRemoteAddr("198.51.100.50");
        untrustedDirectRequest.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(RateLimitInterceptor.resolveClientAddress(untrustedDirectRequest, trustedProxies))
                .isEqualTo("198.51.100.50");

        MockHttpServletRequest trustedProxyRequestWithSpoofedPrefix = new MockHttpServletRequest();
        trustedProxyRequestWithSpoofedPrefix.setRemoteAddr("10.0.0.1");
        trustedProxyRequestWithSpoofedPrefix.addHeader("X-Forwarded-For", "192.0.2.99, 203.0.113.44, 10.0.0.2");
        assertThat(RateLimitInterceptor.resolveClientAddress(trustedProxyRequestWithSpoofedPrefix, trustedProxies))
                .isEqualTo("203.0.113.44");

        Set<String> cidrTrustedProxies = WebConfig.parseTrustedProxyIps("10.10.0.0/16, 172.16.0.0/12");
        MockHttpServletRequest cidrProxyRequest = new MockHttpServletRequest();
        cidrProxyRequest.setRemoteAddr("10.10.42.9");
        cidrProxyRequest.addHeader("X-Forwarded-For", "198.51.100.77, 172.20.1.5");
        assertThat(RateLimitInterceptor.resolveClientAddress(cidrProxyRequest, cidrTrustedProxies))
                .isEqualTo("198.51.100.77");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> WebConfig.parseTrustedProxyIps("not-an-ip/24"));
    }
}
