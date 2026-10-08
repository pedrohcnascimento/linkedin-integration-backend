package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.ContentDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;
import java.util.Optional;

public interface ContentDraftRepository extends JpaRepository<ContentDraftEntity, UUID> {
    Optional<ContentDraftEntity> findByIdAndAppUser_Id(UUID id, UUID appUserId);
    Page<ContentDraftEntity> findAllByAppUser_Id(UUID appUserId, Pageable pageable);
}
