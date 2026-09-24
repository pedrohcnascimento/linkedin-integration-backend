package com.example.linkedinagent.adapter.out.persistence.repository;

import com.example.linkedinagent.adapter.out.persistence.entity.OpportunityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OpportunityRepository extends JpaRepository<OpportunityEntity, UUID> {
}
