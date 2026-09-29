package com.example.linkedinagent.adapter.out.linkedin;

import com.example.linkedinagent.application.linkedin.LinkedInOAuthConfigurationException;
import com.example.linkedinagent.application.linkedin.LinkedInProviderException;
import com.example.linkedinagent.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class LinkedInOAuthClientAdapterTest {

    @Test
    void buildsAuthorizationUriWithConfiguredRedirectAndRequestedScopes() {
        AppProperties properties = properties();
        LinkedInOAuthClientAdapter adapter = new LinkedInOAuthClientAdapter(RestClient.create(), properties);

        URI uri = adapter.authorizationUri("opaque-state", "openid profile w_member_social");
        var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

        assertThat(uri.getHost()).isEqualTo("www.linkedin.com");
        assertThat(uri.getPath()).isEqualTo("/oauth/v2/authorization");
        assertThat(query.getFirst("client_id")).isEqualTo("test-client");
        assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("http://localhost:8080/api/v1/linkedin/oauth/callback");
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(query.getFirst("state")).isEqualTo("opaque-state");
        assertThat(URLDecoder.decode(query.getFirst("scope"), StandardCharsets.UTF_8))
                .isEqualTo("openid profile w_member_social");
        assertThat(uri.toString()).doesNotContain("test-secret");
    }

    @Test
    void exchangesCodeAsFormAndReadsUserInfoWithBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinkedInOAuthClientAdapter adapter = new LinkedInOAuthClientAdapter(builder.build(), properties());

        server.expect(once(), requestTo("https://www.linkedin.com/oauth/v2/accessToken"))
                .andExpect(method(POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code=authorization-code")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_secret=test-secret")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fv1%2Flinkedin%2Foauth%2Fcallback")))
                .andRespond(withSuccess("""
                        {"access_token":"access-token","expires_in":3600,
                         "refresh_token":"refresh-token","scope":"openid profile"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.linkedin.com/v2/userinfo"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"sub":"member-subject","email":"unused@example.com"}
                        """, MediaType.APPLICATION_JSON));

        var accessToken = adapter.exchangeAuthorizationCode("authorization-code");
        var member = adapter.userInfo(accessToken.value());

        assertThat(accessToken.value()).isEqualTo("access-token");
        assertThat(accessToken.expiresInSeconds()).isEqualTo(3600);
        assertThat(accessToken.refreshToken()).contains("refresh-token");
        assertThat(accessToken.scopes()).contains("openid profile");
        assertThat(member.subject()).isEqualTo("member-subject");
        server.verify();
    }

    @Test
    void convertsProviderFailuresToSanitizedErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinkedInOAuthClientAdapter adapter = new LinkedInOAuthClientAdapter(builder.build(), properties());
        server.expect(requestTo("https://api.linkedin.com/v2/userinfo"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError().body("sensitive-provider-response"));

        assertThatThrownBy(() -> adapter.userInfo("access-token"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn member identity lookup failed.")
                .hasNoCause();
    }

    @Test
    void rejectsMissingCredentialsUntrustedRedirectAndMissingOpenIdScope() {
        AppProperties missingCredentials = properties();
        missingCredentials.getLinkedin().setClientSecret(" ");
        LinkedInOAuthClientAdapter missingCredentialsAdapter =
                new LinkedInOAuthClientAdapter(RestClient.create(), missingCredentials);
        assertThatThrownBy(() -> missingCredentialsAdapter.authorizationUri("state", "openid profile"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn OAuth credentials are not configured.");

        AppProperties untrustedRedirect = properties();
        untrustedRedirect.getLinkedin().setRedirectUri("http://attacker.example/callback");
        LinkedInOAuthClientAdapter untrustedRedirectAdapter =
                new LinkedInOAuthClientAdapter(RestClient.create(), untrustedRedirect);
        assertThatThrownBy(() -> untrustedRedirectAdapter.authorizationUri("state", "openid profile"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn redirect URI must be a trusted HTTPS callback.");

        LinkedInOAuthClientAdapter missingOpenIdAdapter =
                new LinkedInOAuthClientAdapter(RestClient.create(), properties());
        assertThatThrownBy(() -> missingOpenIdAdapter.authorizationUri("state", "profile"))
                .isInstanceOf(LinkedInOAuthConfigurationException.class)
                .hasMessage("LinkedIn OAuth requires the openid scope.");
    }

    @Test
    void rejectsIncompleteTokenAndUserInfoResponses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinkedInOAuthClientAdapter adapter = new LinkedInOAuthClientAdapter(builder.build(), properties());
        server.expect(once(), requestTo("https://www.linkedin.com/oauth/v2/accessToken"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.linkedin.com/v2/userinfo"))
                .andRespond(withSuccess("""
                        {"email":"not-required@example.com"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.exchangeAuthorizationCode("authorization-code"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn returned an incomplete token response.");
        assertThatThrownBy(() -> adapter.userInfo("access-token"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn returned an incomplete member identity.");
        server.verify();
    }

    @Test
    void convertsTokenEndpointTimeoutToSanitizedProviderError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LinkedInOAuthClientAdapter adapter = new LinkedInOAuthClientAdapter(builder.build(), properties());
        server.expect(once(), requestTo("https://www.linkedin.com/oauth/v2/accessToken"))
                .andRespond(withException(new IOException("simulated timeout")));

        assertThatThrownBy(() -> adapter.exchangeAuthorizationCode("authorization-code"))
                .isInstanceOf(LinkedInProviderException.class)
                .hasMessage("LinkedIn token exchange failed.")
                .hasNoCause();
        server.verify();
    }

    private static AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getLinkedin().setClientId("test-client");
        properties.getLinkedin().setClientSecret("test-secret");
        properties.getLinkedin().setRedirectUri("http://localhost:8080/api/v1/linkedin/oauth/callback");
        return properties;
    }
}
