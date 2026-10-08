package com.example.linkedinagent.adapter.out.linkedin;

import com.example.linkedinagent.application.publishing.LinkedInPublishingException;
import com.example.linkedinagent.config.AppProperties;
import com.example.linkedinagent.domain.publishing.LinkedInPublicationCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;

class LinkedInPublishingClientAdapterTest {

    private MockRestServiceServer server;
    private LinkedInPublishingClientAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new LinkedInPublishingClientAdapter(builder.build(), properties());
    }

    @Test
    void publishesTextPostWithOfficialHeadersAndStoresResponseIdentifier() {
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(header("X-Restli-Protocol-Version", "2.0.0"))
                .andExpect(header("Linkedin-Version", "202609"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.author").value("urn:li:person:member-123"))
                .andExpect(jsonPath("$.commentary").value("Hello LinkedIn"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.distribution.feedDistribution").value("MAIN_FEED"))
                .andExpect(jsonPath("$.lifecycleState").value("PUBLISHED"))
                .andRespond(withStatus(CREATED).header("x-restli-id", "urn:li:share:123"));

        var result = adapter.publish(
                "access-token",
                new LinkedInPublicationCommand(
                        "member-123", "Hello LinkedIn", LinkedInPublicationCommand.Visibility.PUBLIC));

        assertThat(result.externalPostId()).isEqualTo("urn:li:share:123");
        server.verify();
    }

    @Test
    void mapsProviderRateLimitWithoutExposingProviderResponse() {
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
                .andRespond(withStatus(TOO_MANY_REQUESTS).body("private provider payload"));

        assertThatThrownBy(() -> adapter.publish(
                "access-token",
                new LinkedInPublicationCommand(
                        "member-123", "Hello LinkedIn", LinkedInPublicationCommand.Visibility.PUBLIC)))
                .isInstanceOf(LinkedInPublishingException.class)
                .satisfies(exception -> {
                    var publishingException = (LinkedInPublishingException) exception;
                    assertThat(publishingException.getFailureCode()).isEqualTo("LINKEDIN_RATE_LIMITED");
                    assertThat(publishingException.getMessage()).doesNotContain("private provider payload");
                });
        server.verify();
    }

    @Test
    void rejectsSuccessfulProviderResponseWithoutRestliIdentifier() {
        server.expect(requestTo("https://api.linkedin.com/rest/posts"))
                .andRespond(withStatus(CREATED));

        assertThatThrownBy(() -> adapter.publish(
                "access-token",
                new LinkedInPublicationCommand(
                        "member-123", "Hello LinkedIn", LinkedInPublicationCommand.Visibility.PUBLIC)))
                .isInstanceOf(LinkedInPublishingException.class)
                .hasMessage("LinkedIn returned no publication identifier.");
        server.verify();
    }

    @Test
    void rejectsMissingAccessTokenBeforeMakingNetworkRequest() {
        assertThatThrownBy(() -> adapter.publish(
                "",
                new LinkedInPublicationCommand(
                        "member-123", "Hello LinkedIn", LinkedInPublicationCommand.Visibility.PUBLIC)))
                .isInstanceOf(LinkedInPublishingException.class)
                .hasMessage("LinkedIn access token is missing.");
    }

    private AppProperties properties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getLinkedin().setApiBaseUrl("https://api.linkedin.com");
        appProperties.getLinkedin().setApiVersion("202609");
        return appProperties;
    }
}
