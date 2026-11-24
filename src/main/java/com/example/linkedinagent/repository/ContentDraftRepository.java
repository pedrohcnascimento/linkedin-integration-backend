package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.ContentDraft;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentDraftRepository extends JpaRepository<ContentDraft, UUID> {
    Optional<ContentDraft> findByIdAndAppUser_Id(UUID id, UUID appUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContentDraft> findLockedByIdAndAppUser_Id(UUID id, UUID appUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select draft from ContentDraft draft where draft.id = :id")
    Optional<ContentDraft> findLockedById(@Param("id") UUID id);

    @Query("""
            select draft.id
            from ContentDraft draft
            where draft.status = :status
              and draft.scheduledAt is not null
              and draft.scheduledAt <= :upperBound
            order by draft.scheduledAt asc
            """)
    List<UUID> findScheduledDraftIdsUpTo(
            @Param("status") String status,
            @Param("upperBound") Instant upperBound,
            Pageable pageable);

    Page<ContentDraft> findAllByAppUser_Id(UUID appUserId, Pageable pageable);
}
