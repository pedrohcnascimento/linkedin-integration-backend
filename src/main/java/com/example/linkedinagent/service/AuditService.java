package com.example.linkedinagent.service;

import com.example.linkedinagent.domain.AppUser;
import com.example.linkedinagent.domain.AuditEvent;
import com.example.linkedinagent.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final Clock clock;

    public AuditService(AuditEventRepository auditEventRepository, Clock clock) {
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
    }

    @Transactional
    public AuditEvent recordForUser(
            UUID appUserId,
            String eventType,
            String resourceType,
            String resourceId,
            String outcome,
            String metadataSanitized) {
        AppUser userReference = appUserId == null ? null : AppUser.reference(appUserId);
        AuditEvent event = AuditEvent.record(
                userReference,
                eventType,
                resourceType,
                resourceId,
                outcome,
                clock.instant(),
                metadataSanitized);
        return auditEventRepository.saveAndFlush(event);
    }

    @Transactional
    public AuditEvent recordSystemEvent(
            String eventType,
            String resourceType,
            String resourceId,
            String outcome,
            String metadataSanitized) {
        return recordForUser(null, eventType, resourceType, resourceId, outcome, metadataSanitized);
    }
}
