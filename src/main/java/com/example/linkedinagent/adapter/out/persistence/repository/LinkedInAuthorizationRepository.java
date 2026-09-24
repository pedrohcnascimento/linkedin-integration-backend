package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.LinkedInAuthorizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface LinkedInAuthorizationRepository extends JpaRepository<LinkedInAuthorizationEntity, UUID> {
    Optional<LinkedInAuthorizationEntity> findByAppUser_Id(UUID appUserId);
}
