package com.example.linkedinagent.config;

import com.example.linkedinagent.adapter.out.linkedin.LinkedInOAuthClientAdapter;
import com.example.linkedinagent.application.ports.out.LinkedInOAuthClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class LinkedInOAuthClientConfig {

    @Bean
    @Qualifier("linkedinRestClient")
    RestClient linkedinRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient.Builder builder = RestClient.builder();
        return builder.requestFactory(requestFactory).build();
    }

    @Bean
    LinkedInOAuthClient linkedInOAuthClient(
            @Qualifier("linkedinRestClient") RestClient restClient,
            AppProperties appProperties) {
        return new LinkedInOAuthClientAdapter(restClient, appProperties);
    }
}
