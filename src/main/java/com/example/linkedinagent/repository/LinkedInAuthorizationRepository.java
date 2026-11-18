package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.LinkedInAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LinkedInAuthorizationRepository extends JpaRepository<LinkedInAuthorization, UUID> {
    Optional<LinkedInAuthorization> findByAppUser_Id(UUID appUserId);
}
