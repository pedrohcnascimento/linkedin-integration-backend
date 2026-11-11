package com.example.linkedinagent.client;

import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.exception.LinkedInOAuthConfigurationException;
import com.example.linkedinagent.exception.LinkedInProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LinkedInOAuthHttpClientTest {

    private MockRestServiceServer server;
    private AppProperties properties;
    private LinkedInOAuthHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = validProperties();
        client = new LinkedInOAuthHttpClient(builder.build(), properties);
    }

    @Test
    void buildsEncodedAuthorizationUriForValidConfiguration() {
        URI uri = client.authorizationUri("state-123", "openid profile w_member_social");

        assertThat(uri.toString()).isEqualTo(
                "https://www.linkedin.com/oauth/v2/authorization"
                        + "?response_type=code"
                        + "&client_id=client-id"
                        + "&redirect_uri=https://app.example.com/api/v1/linkedin/oauth/callback"
                        + "&state=state-123"
                        + "&scope=openid%20profile%20w_member_social");
    }

    @Test
    void allowsLocalhostHttpCallbackAndRejectsInsecureOrInvalidConfiguration() {
        properties.getLinkedin().setRedirectUri("http://localhost:8080/api/v1/linkedin/oauth/callback");
        assertThat(client.authorizationUri("state-1", "openid profile").getHost()).isEqualTo("www.linkedin.com");

        LinkedInOAuthHttpClient prodClient =
                new LinkedInOAuthHttpClient(RestClient.builder().build(), properties, false);
        assertThatThrownBy(() -> prodClient.authorizationUri("state-1", "openid profile"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn redirect URI must be a trusted HTTPS callback.");

        properties.getLinkedin().setRedirectUri("http://evil.example.com/callback");
        assertThatThrownBy(() -> client.authorizationUri("state-1", "openid profile"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn redirect URI must be a trusted HTTPS callback.");

        properties.getLinkedin().setRedirectUri("https://user@app.example.com/callback");
        assertThatThrownBy(() -> client.authorizationUri("state-1", "openid profile"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn redirect URI must be a trusted HTTPS callback.");

        properties.getLinkedin().setRedirectUri("https://app.example.com/callback");
        assertThatThrownBy(() -> client.authorizationUri("state-1", "profile w_member_social"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn OAuth requires the openid scope.");

        properties.getLinkedin().setClientSecret(" ");
        assertThatThrownBy(() -> client.authorizationUri("state-1", "openid profile"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn OAuth credentials are not configured.");
    }

    @Test
    void exchangesAuthorizationCodeAndRejectsIncompleteOrFailingResponses() {
        server.expect(requestTo("https://www.linkedin.com/oauth/v2/accessToken"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andRespond(withSuccess("""
                        {"access_token":"token-xyz","expires_in":5184000,"refresh_token":"refresh-xyz","scope":"openid profile"}
                        """, MediaType.APPLICATION_JSON));

        LinkedInOAuthClient.AccessToken token = client.exchangeAuthorizationCode("auth-code-1");
        assertThat(token.value()).isEqualTo("token-xyz");
        assertThat(token.expiresInSeconds()).isEqualTo(5184000L);
        assertThat(token.refreshToken()).contains("refresh-xyz");
        assertThat(token.scopes()).contains("openid profile");
        server.verify();

        server.reset();
        server.expect(requestTo("https://www.linkedin.com/oauth/v2/accessToken"))
                .andRespond(withSuccess("{\"access_token\":\"only-token\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.exchangeAuthorizationCode("auth-code-2"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn returned an incomplete token response.");

        server.reset();
        server.expect(requestTo("https://www.linkedin.com/oauth/v2/accessToken"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("sensitive provider error"));
        assertThatThrownBy(() -> client.exchangeAuthorizationCode("auth-code-3"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn token exchange failed.");
    }

    @Test
    void fetchesUserInfoAndRejectsMissingSubjectOrProviderFailure() {
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-xyz"))
                .andRespond(withSuccess("{\"sub\":\"member-sub-123\"}", MediaType.APPLICATION_JSON));

        LinkedInOAuthClient.LinkedInMember member = client.userInfo("token-xyz");
        assertThat(member.subject()).isEqualTo("member-sub-123");
        server.verify();

        server.reset();
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
                .andRespond(withSuccess("{\"sub\":\"   \"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.userInfo("token-xyz"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn returned an incomplete member identity.");

        server.reset();
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.userInfo("token-xyz"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn member identity lookup failed.");
    }

    private AppProperties validProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getLinkedin().setClientId("client-id");
        appProperties.getLinkedin().setClientSecret("client-secret");
        appProperties.getLinkedin().setRedirectUri("https://app.example.com/api/v1/linkedin/oauth/callback");
        appProperties.getLinkedin().setScopes("openid profile w_member_social");
        return appProperties;
    }
}
