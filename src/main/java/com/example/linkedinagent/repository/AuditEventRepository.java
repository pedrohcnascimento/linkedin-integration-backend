package com.example.linkedinagent.repository;

import com.example.linkedinagent.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findAllByAppUser_IdOrderByOccurredAtDesc(UUID appUserId);

    List<AuditEvent> findAllByResourceIdOrderByOccurredAtDesc(String resourceId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuditEvent event
            set event.appUser = null,
                event.resourceId = null,
                event.metadataSanitized = 'redacted=user_deleted'
            where event.appUser.id = :appUserId
            """)
    int anonymizeEventsForUser(@Param("appUserId") UUID appUserId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from AuditEvent event
            where event.occurredAt <= :cutoff
            """)
    int deleteOccurredBefore(@Param("cutoff") Instant cutoff);
}
