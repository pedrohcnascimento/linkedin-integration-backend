package com.example.linkedinagent.application.ports.out;

import com.example.linkedinagent.domain.publishing.LinkedInPublicationCommand;
import com.example.linkedinagent.domain.publishing.PublicationResult;

public interface LinkedInPublishingPort {
    PublicationResult publish(String accessToken, LinkedInPublicationCommand command);
}
