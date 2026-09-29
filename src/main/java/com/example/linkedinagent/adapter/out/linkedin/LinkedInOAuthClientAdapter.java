package com.example.linkedinagent.adapter.out.linkedin;

import com.example.linkedinagent.application.linkedin.LinkedInOAuthConfigurationException;
import com.example.linkedinagent.application.linkedin.LinkedInProviderException;
import com.example.linkedinagent.application.ports.out.LinkedInOAuthClient;
import com.example.linkedinagent.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

public class LinkedInOAuthClientAdapter implements LinkedInOAuthClient {

    private static final String AUTHORIZATION_ENDPOINT = "https://www.linkedin.com/oauth/v2/authorization";
    private static final String TOKEN_ENDPOINT = "https://www.linkedin.com/oauth/v2/accessToken";
    private static final String USER_INFO_ENDPOINT = "https://api.linkedin.com/v2/userinfo";

    private final RestClient restClient;
    private final AppProperties.Linkedin properties;

    public LinkedInOAuthClientAdapter(RestClient restClient, AppProperties appProperties) {
        this.restClient = restClient;
        this.properties = appProperties.getLinkedin();
    }

    @Override
    public URI authorizationUri(String state, String scopes) {
        validateConfiguration(scopes);
        return UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("state", state)
                .queryParam("scope", scopes)
                .build()
                .encode()
                .toUri();
    }

    @Override
    public AccessToken exchangeAuthorizationCode(String code) {
        validateClientCredentials();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("redirect_uri", properties.getRedirectUri());

        try {
            TokenResponse response = restClient.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                    || response.expiresIn() == null) {
                throw new LinkedInProviderException("LinkedIn returned an incomplete token response.");
            }

            return new AccessToken(
                    response.accessToken(),
                    response.expiresIn(),
                    Optional.ofNullable(response.refreshToken()),
                    Optional.ofNullable(response.scope()));
        } catch (RestClientException exception) {
            throw new LinkedInProviderException("LinkedIn token exchange failed.");
        }
    }

    private void validateConfiguration(String scopes) {
        validateClientCredentials();
        String redirectUri = properties.getRedirectUri();
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new LinkedInOAuthConfigurationException("LinkedIn redirect URI is not configured.");
        }
        URI callback;
        try {
            callback = URI.create(redirectUri);
        } catch (IllegalArgumentException exception) {
            throw new LinkedInOAuthConfigurationException("LinkedIn redirect URI is invalid.");
        }
        boolean secureScheme = "https".equalsIgnoreCase(callback.getScheme());
        boolean localHttpCallback = "http".equalsIgnoreCase(callback.getScheme())
                && ("localhost".equalsIgnoreCase(callback.getHost())
                || "127.0.0.1".equals(callback.getHost())
                || "::1".equals(callback.getHost()));
        if ((!secureScheme && !localHttpCallback)
                || callback.getHost() == null
                || callback.getRawUserInfo() != null
                || callback.getRawFragment() != null) {
            throw new LinkedInOAuthConfigurationException("LinkedIn redirect URI must be a trusted HTTPS callback.");
        }
        if (scopes == null || scopes.isBlank()
                || !java.util.Arrays.asList(scopes.trim().split("\\s+")).contains("openid")) {
            throw new LinkedInOAuthConfigurationException("LinkedIn OAuth requires the openid scope.");
        }
    }

    private void validateClientCredentials() {
        if (properties.getClientId() == null || properties.getClientId().isBlank()
                || properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            throw new LinkedInOAuthConfigurationException("LinkedIn OAuth credentials are not configured.");
        }
    }

    @Override
    public LinkedInMember userInfo(String accessToken) {
        try {
            UserInfoResponse response = restClient.get()
                    .uri(USER_INFO_ENDPOINT)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(UserInfoResponse.class);
            if (response == null || response.subject() == null || response.subject().isBlank()) {
                throw new LinkedInProviderException("LinkedIn returned an incomplete member identity.");
            }
            return new LinkedInMember(response.subject());
        } catch (RestClientException exception) {
            throw new LinkedInProviderException("LinkedIn member identity lookup failed.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("scope") String scope) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserInfoResponse(@JsonProperty("sub") String subject) {
    }
}
