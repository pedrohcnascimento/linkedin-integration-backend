package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findAllByAppUser_IdOrderByOccurredAtDesc(UUID appUserId);

    List<AuditEvent> findAllByResourceIdOrderByOccurredAtDesc(String resourceId);
}
