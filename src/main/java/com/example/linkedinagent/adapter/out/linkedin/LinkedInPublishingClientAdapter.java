package com.example.linkedinagent.adapter.out.linkedin;

import com.example.linkedinagent.application.publishing.LinkedInPublishingException;
import com.example.linkedinagent.application.ports.out.LinkedInPublishingPort;
import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.domain.publishing.LinkedInPublicationCommand;
import com.example.linkedinagent.domain.publishing.PublicationResult;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

public class LinkedInPublishingClientAdapter implements LinkedInPublishingPort {

    private static final String POSTS_PATH = "/rest/posts";
    private final RestClient restClient;
    private final AppProperties.Linkedin properties;

    public LinkedInPublishingClientAdapter(RestClient restClient, AppProperties appProperties) {
        this.restClient = restClient;
        this.properties = appProperties.getLinkedin();
    }

    @Override
    public PublicationResult publish(String accessToken, LinkedInPublicationCommand command) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new LinkedInPublishingException("LINKEDIN_ACCESS_TOKEN_MISSING", "LinkedIn access token is missing.");
        }

        Map<String, Object> payload = Map.of(
                "author", "urn:li:person:" + command.memberSubject(),
                "commentary", command.commentary(),
                "visibility", command.visibility().name(),
                "distribution", Map.of(
                        "feedDistribution", "MAIN_FEED",
                        "targetEntities", List.of(),
                        "thirdPartyDistributionChannels", List.of()),
                "lifecycleState", "PUBLISHED",
                "isReshareDisabledByAuthor", false);

        try {
            var response = restClient.post()
                    .uri(requiredBaseUrl() + POSTS_PATH)
                    .headers(headers -> {
                        headers.setBearerAuth(accessToken);
                        headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
                        headers.set("X-Restli-Protocol-Version", "2.0.0");
                        headers.set("Linkedin-Version", requiredVersion());
                    })
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            String externalPostId = response.getHeaders().getFirst("x-restli-id");
            if (externalPostId == null || externalPostId.isBlank()) {
                throw new LinkedInPublishingException(
                        "LINKEDIN_INVALID_PROVIDER_RESPONSE",
                        "LinkedIn returned no publication identifier.");
            }
            return new PublicationResult(externalPostId);
        } catch (LinkedInPublishingException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw providerHttpException(exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new LinkedInPublishingException(
                    "LINKEDIN_PROVIDER_UNAVAILABLE",
                    "LinkedIn provider request failed.");
        }
    }

    private String requiredVersion() {
        String version = properties.getApiVersion();
        if (version == null || !version.matches("\\d{6}")) {
            throw new LinkedInPublishingException(
                    "LINKEDIN_API_VERSION_INVALID",
                    "LinkedIn API version is not configured correctly.");
        }
        return version;
    }

    private String requiredBaseUrl() {
        String baseUrl = properties.getApiBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()
                || !(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
            throw new LinkedInPublishingException(
                    "LINKEDIN_API_BASE_URL_INVALID",
                    "LinkedIn API base URL is not configured correctly.");
        }
        return baseUrl.replaceAll("/+$", "");
    }

    private LinkedInPublishingException providerHttpException(int status) {
        String code = switch (status) {
            case 400 -> "LINKEDIN_BAD_REQUEST";
            case 401 -> "LINKEDIN_ACCESS_TOKEN_INVALID";
            case 403 -> "LINKEDIN_ACCESS_DENIED";
            case 409 -> "LINKEDIN_WRITE_CONFLICT";
            case 422 -> "LINKEDIN_UNPROCESSABLE_ENTITY";
            case 429 -> "LINKEDIN_RATE_LIMITED";
            case 500 -> "LINKEDIN_PROVIDER_ERROR";
            case 503 -> "LINKEDIN_PROVIDER_UNAVAILABLE";
            default -> "LINKEDIN_PROVIDER_HTTP_ERROR";
        };
        return new LinkedInPublishingException(code, "LinkedIn provider request failed.");
    }
}
