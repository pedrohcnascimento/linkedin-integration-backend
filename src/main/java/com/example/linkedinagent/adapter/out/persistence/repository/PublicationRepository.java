package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.PublicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface PublicationRepository extends JpaRepository<PublicationEntity, UUID> {
    Optional<PublicationEntity> findByAppUser_IdAndRequestFingerprint(UUID appUserId, String requestFingerprint);
}
