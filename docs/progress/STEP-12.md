# STEP-12 — Finalizar a API REST e o contrato OpenAPI (entrega 1)

**Status:** em andamento (iniciado em 30/10/2026 após o adiamento formal do [Passo 11](STEP-11.md)).

## Objetivo desta entrega

Iniciar o Passo 12 com o inventário completo das rotas implementadas frente ao contrato publicado, corrigir divergências imediatas entre código, configuração e documentação, eliminar colisões de schema de erro e fechar o contrato OpenAPI da fatia de **Autenticação e Sessão** (`/api/v1/auth/**` e `/api/v1/users/me`).

## 1. Inventário de rotas de runtime vs. contrato OpenAPI

| Rota e método | Componente | Estado no contrato após a Entrega 1 |
|---|---|---|
| `GET /api/v1/auth/csrf` | `AuthController` | **Revisado na Entrega 1** (`200 CsrfResponse` com schema e exemplo). |
| `POST /api/v1/auth/register` | `AuthController` | **Revisado na Entrega 1** (`201 UserResponse`, `400 INVALID_REQUEST`/`PASSWORD_TOO_LONG`, `403`, `409 EMAIL_ALREADY_REGISTERED`, `429` com `Retry-After`, `503`). |
| `POST /api/v1/auth/login` | `AuthController` | **Revisado na Entrega 1** (`200 UserResponse` explícito no lugar de schema genérico vazio, `400`, `401 AUTHENTICATION_FAILED`, `403`, `429`, `503`). |
| `POST /api/v1/auth/logout` | `SecurityConfig` (`LogoutFilter`) | **Adicionado ao OpenAPI na Entrega 1** via `OpenApiConfig` (`204` e `403`); antes não aparecia em `/api-docs` nem no Swagger UI. |
| `GET /api/v1/users/me` | `AuthController` | **Revisado na Entrega 1** (`200 UserResponse` e `401` sem corpo). |
| `GET /api/v1/linkedin/oauth/start` | `LinkedInOAuthController` | Pendente para a **Entrega 2** (documentar `302` + header `Location`, `401`, `429`, `500`, `503` em vez do `200` implícito). |
| `GET /api/v1/linkedin/oauth/callback` | `LinkedInOAuthController` | Pendente para a **Entrega 2** (substituir retorno wildcard por schema `ConnectionResponse` em `200` e documentar `400`, `429`, `500`, `502`, `503`). |
| `GET /api/v1/linkedin/connection` | `LinkedInOAuthController` | Pendente para a **Entrega 2** (`200 ConnectionResponse` com enumeração `ACTIVE`/`EXPIRED`/`DISCONNECTED` e `401`). |
| `DELETE /api/v1/linkedin/connection` | `LinkedInOAuthController` | Pendente para a **Entrega 2** (`204`, `401`, `403`). |
| `POST /api/v1/drafts` | `DraftController` | Pendente para a **Entrega 2** (completar `@Operation`, `400 INVALID_REQUEST`/`INVALID_DRAFT` e schemas de resposta). |
| `GET /api/v1/drafts` | `DraftController` | Pendente para a **Entrega 2** (`200 DraftPageResponse`, `400 INVALID_PAGINATION`, `401` e limites de `page`/`size`). |
| `PATCH /api/v1/drafts/{id}` | `DraftController` | Pendente para a **Entrega 2** (incluir `400` e `401`, além de schemas/exemplos de erro). |
| `POST /api/v1/drafts/{id}/approve` | `DraftController` | Pendente para a **Entrega 2** (`200 DraftResponse`, `401`, `403`, `404 DRAFT_NOT_FOUND`, `409 DRAFT_INVALID_STATE`). |
| `POST /api/v1/drafts/{id}/publish` | `DraftController` | **Revisado** (contrato OpenAPI concluído no Passo 10; rate limit corrigido no `WebConfig` nesta Entrega 1). |
| `GET /api/v1/publications` | `DraftController` | **Revisado** no Passo 10 (`200 PublicationPageResponse`, `400 INVALID_PAGINATION`, `401`). |
| `GET /actuator/health` | Spring Boot Actuator | Ativo e coberto por teste; `show-details: never`. |

Rotas planejadas **não implementadas** (não devem constar como disponíveis): `GET /api/v1/drafts/{id}` e `/api/v1/opportunities/**` ([Passo 11 adiado](STEP-11.md)).

## 2. Alterações realizadas na Entrega 1

- **Correção de rate limiting na rota de publicação (`WebConfig` e `RateLimitInterceptor`):** `RateLimitInterceptor` já continha a regra `publication-write` para `/api/v1/drafts/{id}/publish` (30/15min por IP, 10/15min por conta e 10/15min por sessão), mas `WebConfig.addInterceptors` não registrava o padrão `/api/v1/drafts/*/publish` e `RateLimitInterceptor` lia apenas `request.getServletPath()` (vazio em requisições com `pathInfo`/MockMvc) em vez do caminho normalizado de aplicação (`ServletRequestPathUtils`). Ambos foram corrigidos e cobertos por dois novos testes em `RateLimitWebIntegrationTest`.
- **Correção da propriedade de desativação do OpenAPI em produção (`application-prod.yml`):** a chave `springdoc` estava indentada acidentalmente sob `spring.springdoc`; foi movida para o nível raiz (`springdoc.api-docs.enabled: false` e `springdoc.swagger-ui.enabled: false`), garantindo que o perfil `prod` sobreponha explicitamente qualquer outro perfil.
- **Unificação do contrato de erro (`ApiError`):** criado o record compartilhado `com.example.linkedinagent.adapter.in.web.ApiError` e removidas as quatro declarações internas duplicadas em `AuthController`, `LinkedInOAuthController`, `DraftController` e `RateLimitExceptionHandler`, eliminando colisão de nomes em `components.schemas.ApiError`.
- **Exposição do logout no OpenAPI (`OpenApiConfig`):** registrado `POST /api/v1/auth/logout` via `OpenApiCustomizer` com respostas `204` e `403`, refletindo o filtro configurado em `SecurityConfig`.
- **Contrato OpenAPI completo de `AuthController` + JSON malformado:** adicionados `@Operation`, `@ApiResponses`, header `Retry-After` em `429`/`503`, `@Schema` nos records `RegisterRequest`, `LoginRequest`, `CsrfResponse` e `UserResponse`, e tratamento de `HttpMessageNotReadableException` como `400 INVALID_REQUEST`.
- **Testes automatizados:** novo teste `openApiDocumentsTheAuthenticationSessionAndLogoutContract` em `ApiDocumentationLocalProfileTest` e dois testes de rate limit em `RateLimitWebIntegrationTest`.

## 3. Validação

`mvn -o -B clean test` executado com sucesso: **64 testes**, 0 falhas, 0 erros e 0 skips (incluindo `ApiDocumentationLocalProfileTest` e `ApiDocumentationProdProfileTest`).

## 4. Pendências para concluir o Passo 12 (Entrega 2)

1. Completar schemas, headers, códigos de status e exemplos OpenAPI em `LinkedInOAuthController` (`/oauth/start` `302` + `Location`, `/oauth/callback` `200 ConnectionResponse` e erros sanitizados, `/connection` `GET` e `DELETE 204`).
2. Completar `@ApiResponses`, limites de parâmetros (`page`, `size`), tratamento de JSON malformado (`HttpMessageNotReadableException`) e schemas de `DraftResponse`/`DraftPageResponse` nos endpoints restantes de `DraftController` (`POST /drafts`, `GET /drafts`, `PATCH /drafts/{id}`, `POST /drafts/{id}/approve`).
3. Adicionar verificação automatizada garantindo que todas as rotas `/api/v1/**` expostas em runtime estejam documentadas em `/api-docs` no perfil `local` e permaneçam indisponíveis (`404`) no perfil `prod`.
