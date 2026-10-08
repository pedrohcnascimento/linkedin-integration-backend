package com.example.linkedinagent.config;

import com.example.linkedinagent.adapter.out.linkedin.LinkedInPublishingClientAdapter;
import com.example.linkedinagent.application.ports.out.LinkedInPublishingPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LinkedInPublishingClientConfig {

    @Bean
    LinkedInPublishingPort linkedInPublishingPort(
            @Qualifier("linkedinRestClient") RestClient restClient,
            AppProperties appProperties) {
        return new LinkedInPublishingClientAdapter(restClient, appProperties);
    }
}
