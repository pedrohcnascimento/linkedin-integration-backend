package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.Publication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PublicationRepository extends JpaRepository<Publication, UUID> {
    @EntityGraph(attributePaths = "draft")
    Optional<Publication> findByAppUser_IdAndRequestFingerprint(UUID appUserId, String requestFingerprint);

    boolean existsByAppUser_IdAndDraft_IdAndStatus(UUID appUserId, UUID draftId, String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Publication publication
            set publication.status = 'OUTCOME_UNKNOWN',
                publication.failureCode = 'LINKEDIN_RESULT_UNKNOWN'
            where publication.status = 'PUBLISHING'
              and publication.createdAt <= :cutoff
            """)
    int markTimedOutPublishingAsOutcomeUnknown(@Param("cutoff") Instant cutoff);

    Page<Publication> findAllByAppUser_Id(UUID appUserId, Pageable pageable);
}
