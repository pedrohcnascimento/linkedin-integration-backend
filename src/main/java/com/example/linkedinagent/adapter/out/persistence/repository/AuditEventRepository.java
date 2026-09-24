package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
}
