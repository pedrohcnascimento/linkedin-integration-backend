package com.example.linkedinagent.client;

import com.example.linkedinagent.domain.LinkedInPublicationCommand;
import com.example.linkedinagent.domain.PublicationResult;

public interface LinkedInPublishingClient {

    PublicationResult publish(String accessToken, LinkedInPublicationCommand command);
}
