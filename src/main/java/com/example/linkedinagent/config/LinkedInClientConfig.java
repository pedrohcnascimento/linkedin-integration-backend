package com.example.linkedinagent.config;

import com.example.linkedinagent.client.LinkedInOAuthClient;
import com.example.linkedinagent.client.LinkedInOAuthHttpClient;
import com.example.linkedinagent.client.LinkedInPublishingClient;
import com.example.linkedinagent.client.LinkedInPublishingHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class LinkedInClientConfig {

    @Bean
    @Qualifier("linkedinRestClient")
    RestClient linkedinRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    LinkedInOAuthClient linkedInOAuthClient(
            @Qualifier("linkedinRestClient") RestClient restClient,
            AppProperties appProperties,
            Environment environment) {
        boolean allowLoopbackHttpCallback = environment.acceptsProfiles(Profiles.of("local | test"));
        return new LinkedInOAuthHttpClient(restClient, appProperties, allowLoopbackHttpCallback);
    }

    @Bean
    LinkedInPublishingClient linkedInPublishingClient(
            @Qualifier("linkedinRestClient") RestClient restClient,
            AppProperties appProperties,
            Environment environment) {
        boolean allowLoopbackHttpBaseUrl = environment.acceptsProfiles(Profiles.of("local | test"));
        return new LinkedInPublishingHttpClient(restClient, appProperties, allowLoopbackHttpBaseUrl);
    }
}
