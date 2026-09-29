package com.example.linkedinagent.application.ports.out;

import java.net.URI;
import java.util.Optional;

public interface LinkedInOAuthClient {

    URI authorizationUri(String state, String scopes);

    AccessToken exchangeAuthorizationCode(String code);

    LinkedInMember userInfo(String accessToken);

    record AccessToken(String value, long expiresInSeconds, Optional<String> refreshToken, Optional<String> scopes) {
    }

    record LinkedInMember(String subject) {
    }
}
