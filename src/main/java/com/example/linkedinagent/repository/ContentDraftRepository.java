package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.ContentDraft;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface ContentDraftRepository extends JpaRepository<ContentDraft, UUID> {
    Optional<ContentDraft> findByIdAndAppUser_Id(UUID id, UUID appUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContentDraft> findLockedByIdAndAppUser_Id(UUID id, UUID appUserId);

    Page<ContentDraft> findAllByAppUser_Id(UUID appUserId, Pageable pageable);
}
