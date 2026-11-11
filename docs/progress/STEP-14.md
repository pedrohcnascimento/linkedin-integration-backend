# STEP-14 — Aplicar segurança, LGPD técnica e observabilidade

**Status:** concluído em 11/11/2026.  
**Base metodológica:** [`docs/PLANO_AUDITORIA_SEGURANCA.md`](../PLANO_AUDITORIA_SEGURANCA.md), [`SECURITY.md`](../../SECURITY.md) e [`docs/planejamento-tecnico.md`](../planejamento-tecnico.md) (seções 10, 11 e 15).

## Objetivo da etapa

Executar e consolidar o **Passo 14 — Aplicar segurança, LGPD técnica e observabilidade** tomando o plano de avaliação de segurança (`docs/PLANO_AUDITORIA_SEGURANCA.md`) como guia de verificação:
1. Fechar lacunas de segurança, auditoria, retenção de dados (LGPD técnica), cabeçalhos HTTP e observabilidade identificadas durante a revisão do código contra a matriz `AUTH-*`, `AUTHZ-*`, `CSRF-*`, `CORS-*`, `OAUTH-*`, `PUB-*`, `RATE-*`, `INPUT-*`, `RESOURCE-*`, `DATA-*`, `CONFIG-*`, `DB-*` e `DOC-*`.
2. Formalizar o inventário de ativos sensíveis, fronteiras de confiança, finalidade/retenção LGPD e trilha de auditoria sanitizada (`audit_event`).
3. Distinguir claramente o que está **comprovado por testes automatizados no repositório** do que **requer infraestrutura externa** (Oracle descartável, Redis compartilhado real e proxy reverso de borda).

---

## 1. Achados corrigidos nesta etapa (Template Seção 9 de `PLANO_AUDITORIA_SEGURANCA.md`)

### FINDING-01 — Rotas de escrita de drafts sem cobertura no `RateLimitInterceptor` (`RATE-01`)
- **ID:** `FINDING-01` (`RATE-01`)
- **Severity:** Medium
- **Status:** `FIXED`
- **Affected component/route:** `WebConfig`, `RateLimitInterceptor`, `DraftResource` (`POST /api/v1/drafts`, `PATCH /api/v1/drafts/{id}`, `POST /api/v1/drafts/{id}/approve`)
- **Evidence:** `WebConfig.addInterceptors` registrava apenas `/api/v1/drafts/*/publish`, deixando criação, edição e aprovação de drafts fora do escopo do interceptor, embora `docs/status-atual.md` e `RATE-01` exigissem rate limiting para escrita e publicação de drafts.
- **Remediation:** Registrados `/api/v1/drafts` e `/api/v1/drafts/**` em `WebConfig`; adicionada política `draft-write` (`IP: 60/15min`, `account: 30/15min`, `session: 30/15min`) em `RateLimitInterceptor` sem limitar leituras `GET /api/v1/drafts`; documentados `429` e `503` em `DraftResource`.
- **Regression test:** `RateLimitWebIntegrationTest.rateLimitsDraftWriteRoutesAndIgnoresForgedForwardedHeadersFromUntrustedClients` e `ApiDocumentationLocalProfileTest.openApiDocumentsLinkedInOAuthAndDraftLifecycleContracts`.

### FINDING-02 — Tabela `audit_event` não alimentada pelos serviços em tempo de execução (`DATA-01`, `OAUTH-05`, `PUB-01..05`)
- **ID:** `FINDING-02` (`DATA-01` / LGPD Seção 11)
- **Severity:** Medium
- **Status:** `FIXED`
- **Affected component/route:** `AuditEvent`, `AuditEventRepository`, `AuditService`, `RegistrationService`, `LinkedInOAuthService`, `DraftWorkflowService`
- **Evidence:** A entidade `AuditEvent` e o repositório `AuditEventRepository` existiam desde a migration `V1`, mas nenhum fluxo de aplicação gravava eventos de auditoria (cadastro, início/conclusão/recusa/desconexão OAuth, aprovação de draft e conclusão/falha de publicação).
- **Remediation:** Criado `AuditEvent.record(...)` com bloqueio explícito de marcadores secretos (`v1.`, `Bearer `, `access_token`, `refresh_token`, `client_secret`, `password`, `authorization_code`) em `metadataSanitized`; criado `AuditService` e integrado a `RegistrationService`, `LinkedInOAuthService` e `DraftWorkflowService`.
- **Regression test:** `DomainModelTest.auditEventEnforcesSanitizedMetadataAndRejectsSecretMarkers` e `SecurityObservabilityAndLgpdIntegrationTest.recordsSanitizedAuditTrailAndEnforcesLgpdRetentionAndAccountErasure`.

### FINDING-03 — Retenção indefinida de `oauth_transaction` consumidas/expiradas e ausência de rotina de retenção/exclusão LGPD (`OAUTH-01`, `OAUTH-05`, `DATA-01`)
- **ID:** `FINDING-03` (LGPD Seção 11)
- **Severity:** Low / Hardening
- **Status:** `FIXED`
- **Affected component/route:** `OAuthTransactionRepository`, `LinkedInOAuthService`, `DataRetentionService`
- **Evidence:** `LinkedInOAuthService.disconnect` removia apenas transações OAuth com `consumedAt IS NULL` (`deletePendingForUser`), mantendo hashes de `state` já consumidos ou expirados indefinidamente.
- **Remediation:** Adicionados `deleteAllForUser` e `deleteExpiredOrConsumedBefore` em `OAuthTransactionRepository`; limpeza automática de estados expirados/consumidos há mais de 24h ao iniciar OAuth e via `DataRetentionService.purgeExpiredOAuthTransactions()`; implementada rotina `DataRetentionService.deleteUserAccountData(UUID)` para exclusão local de conta, tokens cifrados, rascunhos e publicações (`ON DELETE CASCADE`) com anonimização de `audit_event.app_user_id` (`ON DELETE SET NULL`) e registro de `USER_DATA_DELETED`.
- **Regression test:** `SecurityObservabilityAndLgpdIntegrationTest.recordsSanitizedAuditTrailAndEnforcesLgpdRetentionAndAccountErasure`.

### FINDING-04 — Ausência de correlação de requisição (`X-Request-Id` / MDC), logs operacionais de latência e handler fallback de erro (`CONFIG-03`, Seção 10)
- **ID:** `FINDING-04` (`CONFIG-03` / Observabilidade)
- **Severity:** Low / Hardening
- **Status:** `FIXED`
- **Affected component/route:** `RequestCorrelationFilter`, `GlobalExceptionHandler`, `application.yml`, `SecurityConfig`
- **Evidence:** Respostas e logs não possuíam `requestId` correlacionado; `SecurityConfig` não emitia `Referrer-Policy: no-referrer` (importante no callback OAuth para não vazar `code`/`state` via header `Referer`) nem `Permissions-Policy`; e `GlobalExceptionHandler` não possuía handler fallback explícito para `RuntimeException` inesperadas.
- **Remediation:**
  - Criado `RequestCorrelationFilter` validando `X-Request-Id` contra caracteres de controle/CRLF (`^[A-Za-z0-9._-]{1,64}$`), gerando UUID seguro quando ausente/inválido e propagando no MDC (`[req:%X{requestId:-none}]`) e no header de resposta `X-Request-Id`.
  - Configurados `Referrer-Policy: no-referrer` e `Permissions-Policy: geolocation=(), microphone=(), camera=()` em `SecurityConfig`.
  - Adicionado `@ExceptionHandler(RuntimeException.class)` em `GlobalExceptionHandler` preservando exceções do Spring (`ErrorResponse`, `AccessDeniedException`, `AuthenticationException`) e convertendo falhas inesperadas em `500 INTERNAL_ERROR` (`{"code": "INTERNAL_ERROR", "message": "An unexpected error occurred."}`) sem stack trace ou segredos.
- **Regression test:** `SecurityObservabilityAndLgpdIntegrationTest.emitsRequestCorrelationHeaderSecurityHeadersAndRestrictsActuatorExposure` e `SecurityObservabilityAndLgpdIntegrationTest.globalExceptionHandlerSanitizesUnexpectedRuntimeExceptionsAndRethrowsSecurityExceptions`.

---

## 2. Matriz de Privacidade e LGPD Técnica (Seção 11 de `planejamento-tecnico.md`)

| Categoria de dado | Tabela / Armazenamento | Finalidade técnica | Minimização / Proteção | Política de retenção e exclusão |
|---|---|---|---|---|
| Conta local (`email`, `displayName`, `passwordHash`) | `app_user` | Autenticação local e isolamento multiusuário | E-mail normalizado; senha exclusivamente em BCrypt (limite de 72 bytes UTF-8); hash nunca retornado em DTOs ou logs | Mantido enquanto a conta estiver ativa; `DataRetentionService.deleteUserAccountData` apaga a conta e dispara `ON DELETE CASCADE` nos recursos dependentes |
| Tokens OAuth LinkedIn (`encryptedAccessToken`, `encryptedRefreshToken`, `memberSubject`, `scopes`, `expiresAt`) | `linkedin_authorization` | Publicação textual autorizada em nome do membro (`POST /rest/posts`) | Tokens cifrados em repouso com AES-256-GCM (`v1.<nonce+ciphertext+tag>`); chave de 32 bytes fora do banco; nunca retornados pela API nem gravados em logs/auditoria | Removidos imediatamente em `DELETE /api/v1/linkedin/connection` (`oauthService.disconnect`) ou na exclusão da conta local |
| Transação OAuth temporária (`stateHash`, `scopes`, `expiresAt`, `consumedAt`) | `oauth_transaction` | Proteção CSRF e vínculo da sessão ao callback OAuth | Apenas o hash SHA-256 Base64URL do `state` é salvo (`state_hash`); validade máxima de 10 minutos; consumo atômico único | Estados expirados e estados consumidos há > 24h são expurgados em `oauthService.start` e por `DataRetentionService.purgeExpiredOAuthTransactions()` |
| Rascunhos e histórico de publicação (`text`, `title`, `externalPostId`, `requestFingerprint`, `status`, `failureCode`) | `content_draft`, `publication` | Aprovação explícita, idempotência e rastreabilidade de publicações do usuário | Isolamento estrito por `appUserId`; texto do rascunho não é copiado para logs nem para `audit_event` | Vinculados ao usuário (`ON DELETE CASCADE`); remoção de draft preserva publicação com `draft_id = NULL` (`ON DELETE SET NULL`) |
| Sujeitos de rate limiting (`ip`, `account`, `session`, `state`) | Memória (`local`/`test`) ou Redis com TTL (`prod`) | Prevenção de abuso e força bruta | Identificadores nunca são salvos em claro; chaves usam `HMAC-SHA256(dimension + "\0" + identity)` com `RATE_LIMIT_HMAC_KEY` | Expiração automática pela janela fixa (`PEXPIRE` no Redis / limpeza por janela em memória: 15 min, 1h ou 24h) |
| Trilha de auditoria (`eventType`, `resourceType`, `resourceId`, `outcome`, `metadataSanitized`) | `audit_event` | Rastreabilidade operacional, segurança e reconciliação manual | `AuditEvent.record` bloqueia qualquer marcador de token/segredo em `metadataSanitized`; não armazena senhas, tokens, códigos OAuth nem texto de rascunho | Em caso de exclusão da conta local, `app_user_id` passa a `NULL` (`ON DELETE SET NULL`), preservando apenas o registro técnico anonimizado |

---

## 3. Matriz de Verificação de Segurança (`docs/PLANO_AUDITORIA_SEGURANCA.md` Seção 6)

| ID | Área | Status | Evidência automatizada / Observação operacional |
|---|---|---|---|
| `AUTH-01` | Authentication | ✅ Verificado | `AuthResourceIntegrationTest`, `DraftResourceIntegrationTest` (`401` sem sessão em todas as rotas protegidas) |
| `AUTH-02` | Authentication | ✅ Verificado | `AuthResourceIntegrationTest` (sessão inválida/ausente rejeitada com `401`) |
| `AUTH-03` | Session lifecycle | ✅ Verificado | `AuthResourceIntegrationTest.logoutInvalidatesSessionAndBlocksSubsequentProtectedCalls` |
| `AUTH-04` | Session fixation | ✅ Verificado | `AuthResourceIntegrationTest.loginCreatesSessionAndCurrentUserRequiresThatSession` (rotação de `JSESSIONID`) |
| `AUTH-05` | Account enumeration | ✅ Verificado | `AuthResourceIntegrationTest` (login retorna `401 AUTHENTICATION_FAILED` genérico para conta inexistente ou senha incorreta) |
| `AUTH-06` | Rate limiting | ✅ Verificado | `RateLimitWebIntegrationTest`, `RateLimitStoreTest` |
| `AUTH-07` | Password handling | ✅ Verificado | `RegistrationServiceTest`, `DtoMappingTest`, `AuthResourceIntegrationTest`, `SecurityObservabilityAndLgpdIntegrationTest` |
| `AUTHZ-01` | Object authorization | ✅ Verificado | `DraftResourceIntegrationTest` (usuário A não lista nem acessa rascunhos do usuário B) |
| `AUTHZ-02` | Object authorization | ✅ Verificado | `DraftResourceIntegrationTest` (usuário A recebe `404 DRAFT_NOT_FOUND` ao editar, aprovar ou publicar draft de B) |
| `AUTHZ-03` | Object authorization | ✅ Verificado | `DraftResourceIntegrationTest`, `AuthResourceIntegrationTest` (histórico de publicações e `/linkedin/connection` isolados por sessão) |
| `AUTHZ-04` | Property authorization | ✅ Verificado | DTOs de entrada (`RegisterRequest`, `LoginRequest`, `CreateDraftRequest`, `EditDraftRequest`) expõem apenas campos permitidos; `id`, `appUserId`, `status` e `approvedAt` são controlados exclusivamente pelo domínio |
| `AUTHZ-05` | Function authorization | ✅ Verificado | `DraftResourceIntegrationTest`, `DomainModelTest` (transições inválidas retornam `409`/`422`; ausência de CSRF retorna `403`) |
| `CSRF-01` | CSRF | ✅ Verificado | `AuthResourceIntegrationTest`, `DraftResourceIntegrationTest` (`POST`/`PATCH`/`DELETE` sem CSRF retornam `403`) |
| `CSRF-02` | CSRF | ✅ Verificado | `SecurityConfig` (`HttpSessionCsrfTokenRepository` em `!local` vincula o token à sessão HTTP) |
| `CORS-01` | CORS | ✅ Verificado | `WebConfigTest`, `AuthResourceIntegrationTest` (rejeita `*`, caminhos e origens não autorizadas) |
| `OAUTH-01` | OAuth state | ✅ Verificado | `LinkedInOAuthServiceTest`, `AuthResourceIntegrationTest` (`state` ausente, inválido, expirado ou reutilizado retorna `400`) |
| `OAUTH-02` | OAuth redirect | ✅ Verificado | `LinkedInOAuthHttpClientTest` (exige HTTPS ou loopback `localhost`/`127.0.0.1`/`::1`, sem userinfo) |
| `OAUTH-03` | OAuth errors | ✅ Verificado | `LinkedInOAuthServiceTest`, `LinkedInOAuthHttpClientTest` (`state` consumido antes da chamada externa; falhas não permitem replay) |
| `OAUTH-04` | Token protection | ✅ Verificado | `AesGcmTokenCipherTest`, `DtoMappingTest`, `SecurityObservabilityAndLgpdIntegrationTest` (`Referrer-Policy: no-referrer`, AES-256-GCM em repouso, zero tokens em DTOs/logs/auditoria) |
| `OAUTH-05` | Disconnect | ✅ Verificado | `LinkedInOAuthServiceTest`, `SecurityObservabilityAndLgpdIntegrationTest` (remove tokens e transações locais e grava `LINKEDIN_CONNECTION_DISCONNECTED` com `remoteRevocation=not_supported`) |
| `PUB-01..03`, `PUB-05..06` | Publishing & Idempotency | ✅ Verificado | `DraftResourceIntegrationTest`, `DraftWorkflowServiceTest`, `LinkedInPublishingHttpClientTest`, `docs/reconciliacao-manual-publicacoes.md` |
| `PUB-04`, `DB-01` | Oracle & Concorrência real | ⏳ Pendente de infra | Requer instância Oracle descartável (pendência documentada do Passo 10) |
| `RATE-01` | Rate limiting (todas as rotas) | ✅ Verificado | `RateLimitWebIntegrationTest` (login, cadastro, OAuth start/callback, escrita de drafts e publicação) |
| `RATE-02` | Distributed rate limiting | ⚠️ Parcial (unitário) | `RateLimitStoreTest` valida o script Lua atômico de `RedisRateLimitStore`; validação multi-instância requer Redis real |
| `RATE-03` | Proxy / IP spoofing | ✅ Verificado | `RateLimitWebIntegrationTest.rateLimitsDraftWriteRoutesAndIgnoresForgedForwardedHeadersFromUntrustedClients` (`X-Forwarded-For` e `Forwarded` forjados são ignorados) |
| `RATE-04` | Redis outage (fail-closed) | ✅ Verificado | `RateLimitWebIntegrationTest.failsClosedWhenRateLimitStoreIsUnavailable`, `RateLimitStoreTest` (`503 RATE_LIMIT_UNAVAILABLE` + `Retry-After: 5`) |
| `INPUT-01..03` | Input validation & SSRF | ✅ Verificado | `DraftResourceIntegrationTest`, `DomainModelTest` (rejeita `originalUrl` e mídia; backend só se comunica com `api.linkedin.com` configurado) |
| `RESOURCE-01..03` | Pagination & Timeouts | ✅ Verificado | `DraftResourceIntegrationTest` (`size` limitado a `1..50`), `LinkedInClientConfig` (`connectTimeout=5s`, `readTimeout=10s`), `DraftWorkflowServiceTest` |
| `DATA-01` | Sensitive data | ✅ Verificado | `SecurityObservabilityAndLgpdIntegrationTest`, `DomainModelTest`, `DtoMappingTest` |
| `CONFIG-01` | Production profile | ✅ Verificado | `ApiDocumentationProdProfileTest` (`/api-docs` e `/swagger-ui/**` retornam `404` em `prod`) |
| `CONFIG-02` | Actuator exposure | ✅ Verificado | `SecurityObservabilityAndLgpdIntegrationTest` (`/actuator/health` sem detalhes internos; `/actuator/env`, `/beans`, `/configprops` bloqueados) |
| `CONFIG-03` | Error handling | ✅ Verificado | `GlobalExceptionHandler`, `SecurityObservabilityAndLgpdIntegrationTest` (`500 INTERNAL_ERROR` sanitizado sem stack trace) |
| `DB-02` | Database integrity (SQLite) | ✅ Verificado | `SqlitePersistenceAndMigrationIntegrationTest` (Flyway `V1 -> V2`, `PRAGMA foreign_keys = ON`, `ON DELETE CASCADE` / `SET NULL`, índice único case-insensitive) |
| `DOC-01` | API inventory parity | ✅ Verificado | `ApiDocumentationLocalProfileTest.everyImplementedApiV1RouteIsPresentInOpenApiAndPlannedRoutesAreAbsent` |

---

## 4. Arquivos adicionados ou atualizados

- **Código de produção:**
  - `src/main/java/com/example/linkedinagent/domain/AuditEvent.java`
  - `src/main/java/com/example/linkedinagent/repository/AuditEventRepository.java`
  - `src/main/java/com/example/linkedinagent/repository/OAuthTransactionRepository.java`
  - `src/main/java/com/example/linkedinagent/service/AuditService.java`
  - `src/main/java/com/example/linkedinagent/service/DataRetentionService.java`
  - `src/main/java/com/example/linkedinagent/service/RegistrationService.java`
  - `src/main/java/com/example/linkedinagent/service/LinkedInOAuthService.java`
  - `src/main/java/com/example/linkedinagent/service/DraftWorkflowService.java`
  - `src/main/java/com/example/linkedinagent/security/RequestCorrelationFilter.java`
  - `src/main/java/com/example/linkedinagent/security/RateLimitInterceptor.java`
  - `src/main/java/com/example/linkedinagent/config/SecurityConfig.java`
  - `src/main/java/com/example/linkedinagent/config/WebConfig.java`
  - `src/main/java/com/example/linkedinagent/exception/GlobalExceptionHandler.java`
  - `src/main/java/com/example/linkedinagent/resource/DraftResource.java`
  - `src/main/resources/application.yml`
- **Testes automatizados:**
  - `src/test/java/com/example/linkedinagent/security/SecurityObservabilityAndLgpdIntegrationTest.java` (novo)
  - `src/test/java/com/example/linkedinagent/domain/DomainModelTest.java` (expandido)
  - `src/test/java/com/example/linkedinagent/resource/RateLimitWebIntegrationTest.java` (expandido)
  - `src/test/java/com/example/linkedinagent/ApiDocumentationLocalProfileTest.java` (expandido)
- **Documentação:**
  - `docs/progress/STEP-14.md`, `docs/PLANO_AUDITORIA_SEGURANCA.md`, `docs/planejamento-tecnico.md`, `docs/status-atual.md` e `README.md`.

## 5. Comandos executados e resultado

- `mvn -o -B test`: **98 testes executados em 20 classes de teste, 0 falhas, 0 erros, 0 ignorados**.

## 6. Pendências externas que dependem de infraestrutura

- **Oracle descartável (`PUB-04`, `DB-01` — Passo 10):** executar migrations `db/migration/oracle`, validação Hibernate e testes de concorrência real com lock pessimista assim que houver instância Oracle disponível.
- **Redis compartilhado e proxy reverso (`RATE-02`, ambiente de deploy):** validar compartilhamento de contadores entre múltiplas instâncias e preservação de IP na borda antes de exposição pública.
- **Scans externos de pipeline/DAST (`Phase 2` / `Phase 3` de `PLANO_AUDITORIA_SEGURANCA.md`):** CodeQL no GitHub Actions, OWASP Dependency-Check com feed NVD atualizado e OWASP ZAP passivo sobre o ambiente de homologação devem ser executados no pipeline/ambiente alvo (Passo 15/16).

## 7. Próximo passo recomendado

Avançar para o **Passo 15 — Dockerizar e documentar a execução** (ou concluir a validação em Oracle descartável do Passo 10 caso o Docker/ambiente de banco esteja disponível na máquina).
