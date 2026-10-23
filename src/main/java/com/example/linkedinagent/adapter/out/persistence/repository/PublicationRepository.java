package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.PublicationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.Optional;

public interface PublicationRepository extends JpaRepository<PublicationEntity, UUID> {
    @EntityGraph(attributePaths = "draft")
    Optional<PublicationEntity> findByAppUser_IdAndRequestFingerprint(UUID appUserId, String requestFingerprint);

    boolean existsByAppUser_IdAndDraft_IdAndStatus(UUID appUserId, UUID draftId, String status);

    Page<PublicationEntity> findAllByAppUser_Id(UUID appUserId, Pageable pageable);
}
