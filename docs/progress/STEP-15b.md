# STEP-15b — Agendamento automático de publicações na aprovação de rascunhos

**Status:** concluído em 24/11/2026.  
**Base metodológica:** [`docs/planejamento-tecnico.md`](../planejamento-tecnico.md), [`docs/status-atual.md`](../status-atual.md) e [`README.md`](../../README.md).

## 1. Objetivo

Permitir programar data e hora futuras (UTC, ISO-8601) ao aprovar um rascunho (`POST /api/v1/drafts/{id}/approve`) — ou agendar/reagendar/cancelar um rascunho já aprovado (`PUT /api/v1/drafts/{id}/schedule` e `DELETE /api/v1/drafts/{id}/schedule`) — para que o backend publique automaticamente via **Posts API** no horário programado sem exigir login manual no momento do post.

## 2. Arquivos criados e alterados

- **Migrations Flyway (`V3`):**
  - `src/main/resources/db/migration/sqlite/V3__Add_draft_scheduling.sql`
  - `src/main/resources/db/migration/oracle/V3__Add_draft_scheduling.sql`
- **Domínio, DTOs e Repositório:**
  - `src/main/java/com/example/linkedinagent/domain/ContentDraft.java` — coluna `scheduled_at`, estados `SCHEDULED` e `SCHEDULE_EXPIRED`, invariantes de agendamento/reagendamento/cancelamento/expiração.
  - `src/main/java/com/example/linkedinagent/dto/DraftDtos.java` — `ApproveDraftRequest`, `ScheduleDraftRequest` e campo `scheduledAt` em `DraftResponse`.
  - `src/main/java/com/example/linkedinagent/repository/ContentDraftRepository.java` — busca indexada de drafts agendados vencidos e lock pessimista por ID.
- **Aplicação, Segurança e Exposição REST:**
  - `src/main/java/com/example/linkedinagent/service/DraftWorkflowService.java` — orquestração de aprovação com agendamento opcional, validação de expiração do token LinkedIn antes do horário agendado, reserva transacional em duas fases com chave determinística e janela de tolerância (`graceWindow`).
  - `src/main/java/com/example/linkedinagent/service/ScheduledPublicationService.java` — job `@Scheduled` configurável (`app.scheduling.*`).
  - `src/main/java/com/example/linkedinagent/resource/DraftResource.java` — corpo opcional em `POST /api/v1/drafts/{id}/approve`, novas rotas `PUT /api/v1/drafts/{id}/schedule` e `DELETE /api/v1/drafts/{id}/schedule` com documentação OpenAPI completa.
  - `src/main/java/com/example/linkedinagent/security/RateLimitInterceptor.java` — inclusão de `PUT`/`DELETE` `/api/v1/drafts/{id}/schedule` no escopo `draft-write`.
- **Configuração e Testes:**
  - `src/main/resources/application.yml`, `.env.example`, `src/test/resources/application-test.yml`
  - `DomainModelTest`, `SqlitePersistenceAndMigrationIntegrationTest`, `RateLimitInterceptorTest`, `ApiDocumentationLocalProfileTest`, `DraftWorkflowServiceTest` e `DraftResourceIntegrationTest`.

## 3. Decisões e garantias

- **Compatibilidade retroativa:** aprovar sem corpo (ou com `scheduledAt: null`) preserva exatamente o fluxo `DRAFT -> APPROVED`.
- **Janela de tolerância (`PT60M` padrão):** se a aplicação estiver desligada no horário programado e voltar dentro de 60 minutos, publica automaticamente; se passar da janela, marca o draft como `SCHEDULE_EXPIRED` sem postar fora de hora, mantendo a possibilidade de reagendar, cancelar ou publicar manualmente.
- **Prevenção de loops e duplicidade:** falhas conhecidas do LinkedIn registram `FAILED` em `publication` e revertem o draft para `APPROVED` (`scheduledAt = null`); falhas ambíguas registram `OUTCOME_UNKNOWN` e bloqueiam novas tentativas até reconciliação manual.
