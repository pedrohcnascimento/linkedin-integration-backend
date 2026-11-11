# STEP-13 — Completar testes automatizados

**Status:** concluído em 11/11/2026.

## Objetivo da etapa

Consolidar a cobertura de testes automatizados em todas as camadas da arquitetura DDD (`domain`, `dto`, `client`, `security`, `repository`, `service`, `resource` e `config`), cobrindo transições de estado do domínio, mapeadores DTO, clientes HTTP externos simulados (`MockRestServiceServer`), cifragem AES-GCM, rate limiting (HMAC + stores em memória e Redis), upgrade incremental de migrations Flyway (`V1 -> V2`) com dados populados, persistência JPA em SQLite real com `PRAGMA foreign_keys = ON`, proteção contra session fixation e contratos HTTP/OpenAPI — sem depender de rede ou credenciais reais do LinkedIn.

## 1. Cobertura consolidada por camada

| Camada / Pacote | Classes de teste | Escopo verificado |
|---|---|---|
| Bootstrap & Contratos OpenAPI (`com.example.linkedinagent`) | `LinkedinAgentApplicationTests`, `ApiDocumentationLocalProfileTest`, `ApiDocumentationProdProfileTest` | Subida do contexto Spring, disponibilidade de `/actuator/health`, documentação completa e paridade dinâmica das 15 rotas `/api/v1/**` em `local`/`test`, ausência de rotas planejadas não implementadas e bloqueio (`404`) de `/api-docs` e `/swagger-ui/**` em `prod`. |
| Domínio (`domain`) | `DomainModelTest` | Ciclo de vida e invariantes de `ContentDraft` (`DRAFT -> APPROVED -> PUBLISHED`, rejeição de texto em branco, atualização vazia, mídia não textual e URLs de artigo, bloqueio de edição/reaprovação após `APPROVED`), `Publication` (`PUBLISHING`, `PUBLISHED`, `FAILED` determinístico vs. `OUTCOME_UNKNOWN`/`LINKEDIN_RESULT_UNKNOWN`), fronteiras de expiração de `LinkedInAuthorization` (`ACTIVE -> EXPIRED` quando `expiresAt <= now`) e `OAuthTransaction`, `AppUser` e `RateLimitBucket`. |
| DTOs (`dto`) | `DtoMappingTest` | Conversão de `AppUser`/`AppUserPrincipal` para `UserResponse` sem expor `passwordHash`, mapeamento de `ConnectionResponse` (`ACTIVE` e `DISCONNECTED`), `DraftPageResponse` e `PublicationPageResponse` (incluindo `draftId = null` quando o draft associado é removido). |
| Clientes HTTP LinkedIn (`client`) | `LinkedInOAuthHttpClientTest`, `LinkedInPublishingHttpClientTest` | `MockRestServiceServer` cobrindo construção de URI OAuth 2.0, validação de `redirect_uri` (HTTPS ou `localhost`/`127.0.0.1`/`::1`, bloqueando hosts HTTP externos e `userinfo`), exigência do escopo `openid`, troca de `code` por token, consulta OIDC `/v2/userinfo`, publicação em `/rest/posts` com headers Rest.li/`Linkedin-Version`, tratamento de `429`, `409`/`5xx`/ausência de `x-restli-id` como resultado ambíguo e sanitização de payloads de erro do provedor. |
| Segurança & Criptografia (`security`) | `AesGcmTokenCipherTest` | Validação tardia da chave no perfil local, cifragem/decifragem AES-256-GCM com nonces aleatórios distintos (`v1.<base64>`), rejeição de texto vazio, prefixo desconhecido, Base64 malformado, payload truncado, chave incorreta e chaves fora de 32 bytes. |
| Persistência, Conversores, Rate-Limit Store & Migrations (`repository`) | `InstantStringConverterTest`, `RateLimitStoreTest`, `SqlitePersistenceAndMigrationIntegrationTest` | Conversão `Instant <-> ISO-8601` e leitura de epoch-millis legado; janela fixa e expiração em `InMemoryRateLimitStore`; execução de script Lua e fail-closed (`RateLimitStoreUnavailableException` quando retorno é `null`) em `RedisRateLimitStore`; upgrade Flyway `V1 -> V2` sobre banco SQLite populado preservando registros existentes, aplicando `password_hash` default, índice único case-insensitive `uq_app_user_email_normalized` e `ON DELETE SET NULL` / `ON DELETE CASCADE` com `PRAGMA foreign_keys = ON`; e round-trip JPA (`flush` + `clear`) das 7 entidades (`AppUser`, `LinkedInAuthorization`, `OAuthTransaction`, `ContentDraft`, `Publication`, `Opportunity`, `AuditEvent`). |
| Serviços de Aplicação (`service`) | `RegistrationServiceTest`, `RateLimitServiceTest`, `LinkedInOAuthServiceTest`, `DraftWorkflowServiceTest` | Normalização de e-mail/nome, limite BCrypt de 72 bytes UTF-8, tradução de colisão concorrente (`DataIntegrityViolationException -> EmailAlreadyRegisteredException`), hashing HMAC-SHA256 de sujeitos de rate limit sem armazenar identificadores em claro, consumo atômico de `state` antes das chamadas externas, rejeição de `state` expirado, reconexão atualizando autorização existente, desconexão limpando estados pendentes e bloqueio de publicação sem conexão ativa/não expirada ou com colisão de impressão digital. |
| Recursos REST & Segurança Web (`resource`, `config`) | `AuthResourceIntegrationTest`, `DraftResourceIntegrationTest`, `RateLimitWebIntegrationTest`, `WebConfigTest` | Cadastro, login com rotação de `JSESSIONID` contra session fixation e ausência de `password`/`passwordHash` no corpo de login/cadastro/`/users/me`, CSRF, logout, CORS, fluxo OAuth completo, rascunhos, paginação, idempotência, reconciliação manual (`PUBLISHED` e `RECONCILED_NOT_PUBLISHED` com `audit_event`) e limites `429`/`503` com `Retry-After`. |

## 2. Arquivos adicionados ou atualizados

- Adicionados:
  - `src/test/java/com/example/linkedinagent/domain/DomainModelTest.java`
  - `src/test/java/com/example/linkedinagent/dto/DtoMappingTest.java`
  - `src/test/java/com/example/linkedinagent/client/LinkedInOAuthHttpClientTest.java`
  - `src/test/java/com/example/linkedinagent/repository/InstantStringConverterTest.java`
  - `src/test/java/com/example/linkedinagent/repository/RateLimitStoreTest.java`
  - `src/test/java/com/example/linkedinagent/repository/SqlitePersistenceAndMigrationIntegrationTest.java`
  - `src/test/java/com/example/linkedinagent/service/RegistrationServiceTest.java`
  - `src/test/java/com/example/linkedinagent/service/RateLimitServiceTest.java`
- Expandidos:
  - `src/test/java/com/example/linkedinagent/security/AesGcmTokenCipherTest.java`
  - `src/test/java/com/example/linkedinagent/service/LinkedInOAuthServiceTest.java`
  - `src/test/java/com/example/linkedinagent/service/DraftWorkflowServiceTest.java`
  - `src/test/java/com/example/linkedinagent/resource/AuthResourceIntegrationTest.java`
- Documentação atualizada:
  - `README.md`, `docs/planejamento-tecnico.md`, `docs/status-atual.md` e `docs/progress/STEP-13.md`.

## 3. Comandos executados e resultado

- `mvn -o -B test`: **93 testes executados, 0 falhas, 0 erros, 0 ignorados** (aumento de 67 para 93 testes cobrindo todas as 19 classes de teste).

## 4. Limitações e pendências de infraestrutura remanescentes

- A validação de migrations Oracle (`db/migration/oracle`), `ddl-auto=validate` e concorrência real com lock pessimista no perfil `prod` permanece pendente de uma instância Oracle descartável (pendência de infraestrutura do Passo 10).
- Os testes de `RedisRateLimitStore` verificam a invocação do script Lua e o comportamento fail-closed com `StringRedisTemplate` simulado; a validação contra uma instância Redis real deve ser executada no ambiente de staging/produção antes da exposição pública.

## 5. Próximo passo recomendado

Avançar para o **Passo 14 — Aplicar segurança, LGPD técnica e observabilidade** (revisão de retenção/exclusão de dados, auditoria sanitizada, logs estruturados e métricas/observabilidade sem exposição de segredos).
