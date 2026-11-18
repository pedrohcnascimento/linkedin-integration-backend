package com.example.linkedinagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de domínio persistida para oportunidades locais de vagas (tabela {@code opportunity}).
 * Mantida mapeada para compatibilidade com as migrations iniciais; endpoints e serviços ficam
 * adiados para ciclo futuro (ver {@code docs/progress/STEP-11.md}).
 */
@Entity
@Table(name = "opportunity")
public class Opportunity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "external_url", length = 2000)
    private String externalUrl;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;

    @Column(length = 255)
    private String location;

    @Column(name = "description_snapshot", length = 4000)
    private String descriptionSnapshot;

    @Column(name = "match_score")
    private Integer matchScore;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "found_at", nullable = false, length = 64)
    private Instant foundAt;

    @Column(name = "expires_at", length = 64)
    private Instant expiresAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AppUser getAppUser() { return appUser; }
    public void setAppUser(AppUser appUser) { this.appUser = appUser; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getDescriptionSnapshot() { return descriptionSnapshot; }
    public void setDescriptionSnapshot(String descriptionSnapshot) { this.descriptionSnapshot = descriptionSnapshot; }
    public Integer getMatchScore() { return matchScore; }
    public void setMatchScore(Integer matchScore) { this.matchScore = matchScore; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getFoundAt() { return foundAt; }
    public void setFoundAt(Instant foundAt) { this.foundAt = foundAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
