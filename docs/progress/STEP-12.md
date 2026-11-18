# STEP-12 — Finalizar a API REST e o contrato OpenAPI

**Status:** concluído em 30/10/2026 (Entregas 1 e 2; iniciado após o adiamento formal do [Passo 11](STEP-11.md)).

## Objetivo da etapa

Consolidar a API REST `/api/v1/**` e seu contrato OpenAPI (`springdoc-openapi`), eliminando divergências entre código, configuração e documentação, padronizando respostas de erro sanitizadas (`ApiError`) e verificando automaticamente a paridade entre todas as rotas expostas em runtime e o documento `/api-docs` (mantido desativado no perfil `prod`).

## 1. Inventário final de rotas de runtime vs. contrato OpenAPI

| Rota e método | Componente | Estado final no contrato OpenAPI |
|---|---|---|
| `GET /api/v1/auth/csrf` | `AuthController` | **Concluído (Entrega 1)** — `200 CsrfResponse` com schema e exemplo. |
| `POST /api/v1/auth/register` | `AuthController` | **Concluído (Entrega 1)** — `201 UserResponse`, `400 INVALID_REQUEST`/`PASSWORD_TOO_LONG`, `403`, `409 EMAIL_ALREADY_REGISTERED`, `429` (`Retry-After`), `503` (`Retry-After`). |
| `POST /api/v1/auth/login` | `AuthController` | **Concluído (Entrega 1)** — `200 UserResponse` explícito, `400 INVALID_REQUEST`, `401 AUTHENTICATION_FAILED`, `403`, `429` (`Retry-After`), `503` (`Retry-After`). |
| `POST /api/v1/auth/logout` | `SecurityConfig` (`LogoutFilter`) | **Concluído (Entrega 1)** — exposto via `OpenApiConfig` (`204` e `403`). |
| `GET /api/v1/users/me` | `AuthController` | **Concluído (Entrega 1)** — `200 UserResponse` e `401` sem corpo. |
| `GET /api/v1/linkedin/oauth/start` | `LinkedInOAuthController` | **Concluído (Entrega 2)** — `302` com header `Location` (sem `200` implícito), `401`, `429` (`Retry-After`), `500 LINKEDIN_CREDENTIAL_STORAGE_ERROR`, `503 LINKEDIN_OAUTH_NOT_CONFIGURED`/`RATE_LIMIT_UNAVAILABLE`. |
| `GET /api/v1/linkedin/oauth/callback` | `LinkedInOAuthController` | **Concluído (Entrega 2)** — parâmetros `code`/`state`/`error` documentados, `200 ConnectionResponse` explícito, `400 LINKEDIN_AUTHORIZATION_DENIED`/`LINKEDIN_AUTHORIZATION_FAILED`, `429`, `500`, `502 LINKEDIN_PROVIDER_ERROR`, `503`. |
| `GET /api/v1/linkedin/connection` | `LinkedInOAuthController` | **Concluído (Entrega 2)** — `200 ConnectionResponse` (exemplos `ACTIVE` e `DISCONNECTED`; enum `ACTIVE`/`EXPIRED`/`DISCONNECTED`; campos opcionais anuláveis; nunca expõe tokens) e `401`. |
| `DELETE /api/v1/linkedin/connection` | `LinkedInOAuthController` | **Concluído (Entrega 2)** — `204` (sem `200` implícito), `401` e `403`. |
| `POST /api/v1/drafts` | `DraftController` | **Concluído (Entrega 2)** — `@Operation` explícita, `201 DraftResponse`, `400 INVALID_REQUEST`/`INVALID_DRAFT`, `401`, `403`, `422 DRAFT_MEDIA_UNSUPPORTED`. |
| `GET /api/v1/drafts` | `DraftController` | **Concluído (Entrega 2)** — limites de `page` (`>= 0`, default `0`) e `size` (`1..50`, default `20`), `200 DraftPageResponse`, `400 INVALID_PAGINATION`/`INVALID_REQUEST`, `401`. |
| `PATCH /api/v1/drafts/{id}` | `DraftController` | **Concluído (Entrega 2)** — `200 DraftResponse`, `400 INVALID_DRAFT_UPDATE`/`INVALID_DRAFT`/`INVALID_REQUEST`, `401`, `403`, `404 DRAFT_NOT_FOUND`, `409 DRAFT_INVALID_STATE`, `422 DRAFT_MEDIA_UNSUPPORTED`. |
| `POST /api/v1/drafts/{id}/approve` | `DraftController` | **Concluído (Entrega 2)** — `200 DraftResponse`, `400 INVALID_REQUEST`, `401`, `403`, `404 DRAFT_NOT_FOUND`, `409 DRAFT_INVALID_STATE`. |
| `POST /api/v1/drafts/{id}/publish` | `DraftController` | **Concluído (Passo 10 + Entregas 1 e 2)** — contrato de idempotência/falhas, rate limit corrigido no `WebConfig` e header `Retry-After` documentado em `429`/`503`. |
| `GET /api/v1/publications` | `DraftController` | **Concluído (Passo 10 + Entrega 2)** — `200 PublicationPageResponse`, `400 INVALID_PAGINATION`, `401` e limites explícitos de `page`/`size`. |
| `GET /actuator/health` | Spring Boot Actuator | Ativo e coberto por teste; `show-details: never`. |

Rotas planejadas **não implementadas** (verificadas automaticamente como ausentes em `/api-docs`): `GET /api/v1/drafts/{id}` e `/api/v1/opportunities/**` ([Passo 11 adiado](STEP-11.md)).

## 2. Alterações realizadas

### Entrega 1
- **Correção de rate limiting na rota de publicação (`WebConfig` e `RateLimitInterceptor`):** registrado `/api/v1/drafts/*/publish` em `WebConfig.addInterceptors` e atualizado `RateLimitInterceptor` para resolver o caminho normalizado de aplicação via `ServletRequestPathUtils` (com fallback `servletPath + pathInfo`), coberto por `RateLimitWebIntegrationTest`.
- **Correção da desativação do OpenAPI em produção (`application-prod.yml`):** movido `springdoc` para o nível raiz (`springdoc.api-docs.enabled: false` e `springdoc.swagger-ui.enabled: false`).
- **Unificação do contrato de erro (`ApiError`):** extraído o record compartilhado `com.example.linkedinagent.adapter.in.web.ApiError`, eliminando as quatro declarações duplicadas internas.
- **Exposição do logout no OpenAPI (`OpenApiConfig`):** registrado `POST /api/v1/auth/logout` via `OpenApiCustomizer` (`204` e `403`).
- **Contrato completo de `AuthController` + JSON malformado:** adicionados `@Operation`, `@ApiResponses`, `Retry-After`, `@Schema` nos DTOs e tratamento de `HttpMessageNotReadableException` como `400 INVALID_REQUEST`.

### Entrega 2
- **Contrato completo de `LinkedInOAuthController`:** documentados `302` + header `Location` em `GET /oauth/start` (eliminando o `200` implícito), `200 ConnectionResponse` e erros sanitizados (`400`, `429`, `500`, `502`, `503`) em `GET /oauth/callback`, exemplos `ACTIVE`/`DISCONNECTED` e enum `{"ACTIVE", "EXPIRED", "DISCONNECTED"}` em `GET /connection`, e `204`/`401`/`403` em `DELETE /connection`.
- **Contrato completo e validação uniforme em `DraftController`:** completados `@Operation`, `@ApiResponses`, limites `@Schema` de `page`/`size`, headers `Retry-After` e schemas `DraftResponse` (`status` em `{"DRAFT", "APPROVED", "PUBLISHED"}`) / `DraftPageResponse`; ampliado `handleInvalidRequest` para cobrir `MethodArgumentNotValidException`, `HttpMessageNotReadableException` e `MethodArgumentTypeMismatchException` com `400 INVALID_REQUEST` sanitizado.
- **Testes de contrato, validação e paridade de rotas:** adicionados `malformedJsonBeanValidationAndTypeMismatchReturnSanitizedApiError` em `DraftControllerIntegrationTest`, `openApiDocumentsLinkedInOAuthAndDraftLifecycleContracts` e `everyImplementedApiV1RouteIsPresentInOpenApiAndPlannedRoutesAreAbsent` (comparando dinamicamente `RequestMappingHandlerMapping` + `POST /api/v1/auth/logout` contra `/api-docs`) em `ApiDocumentationLocalProfileTest`.

## 3. Validação

`mvn -o -B test` executado com sucesso: **67 testes**, 0 falhas, 0 erros e 0 skips (incluindo `ApiDocumentationLocalProfileTest`, `ApiDocumentationProdProfileTest` e `DraftControllerIntegrationTest`).

## 4. Próximo passo recomendado

Avançar para o **Passo 13 — Completar testes automatizados** (revisão sistemática das camadas unitária, HTTP/MockMvc e integração SQLite, mantendo a validação em Oracle descartável do Passo 10 registrada como pendência de infraestrutura).
