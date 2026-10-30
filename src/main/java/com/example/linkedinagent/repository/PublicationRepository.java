package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.Publication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PublicationRepository extends JpaRepository<Publication, UUID> {
    @EntityGraph(attributePaths = "draft")
    Optional<Publication> findByAppUser_IdAndRequestFingerprint(UUID appUserId, String requestFingerprint);

    boolean existsByAppUser_IdAndDraft_IdAndStatus(UUID appUserId, UUID draftId, String status);

    Page<Publication> findAllByAppUser_Id(UUID appUserId, Pageable pageable);
}
