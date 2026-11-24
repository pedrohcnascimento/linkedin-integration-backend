# STEP-16 — Revisão final de portfólio, consolidação de banco de produção e CI/CD

**Status:** concluído em 24/11/2026.  
**Base metodológica:** [`docs/planejamento-tecnico.md`](../planejamento-tecnico.md) (seções 10, 12 e 15 — Passo 16), [`docs/status-atual.md`](../status-atual.md), [`docs/PLANO_AUDITORIA_SEGURANCA.md`](../PLANO_AUDITORIA_SEGURANCA.md) e [`README.md`](../../README.md).

## 1. Objetivo da etapa

Executar e concluir o **Passo 16 — Revisão de portfólio e CI/CD**, encerrando o ciclo da **v1** do `linkedin-integration-backend`:
1. **Consolidar formalmente o banco de produção suportado (`Oracle`):** eliminar qualquer ambiguidade residual no planejamento técnico sobre PostgreSQL (cujas dependências já haviam sido removidas do `pom.xml`) e demonstrar concorrência multi-thread contra lock pessimista e unicidade de idempotência tanto em SQLite persistido em arquivo quanto em Oracle real via Testcontainers.
2. **Revisar e endurecer o pipeline de CI/CD (`.github/workflows/security-ci.yml`):** garantir execução limpa, reprodutível e segura em GitHub Actions cobrindo suíte automatizada (`mvn -B clean verify`), Testcontainers (Oracle Free 23 + Redis 7.4), smoke test da imagem Docker multi-stage sem root, Gitleaks, CodeQL SAST e OWASP Dependency-Check.
3. **Elevar a qualidade de portfólio e governança do repositório:** revisar `README.md` (badges, diagramas Mermaid de arquitetura DDD e sequência de publicação/agendamento, exemplos `curl` sanitizados, documentação de CI/CD e deploy), `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `docs/status-atual.md` e `docs/reconciliacao-manual-publicacoes.md`.

---

## 2. Entregas e melhorias realizadas

### 2.1. Consolidação do Banco de Produção (`Oracle`) e Testes de Concorrência Multi-Thread (`PUB-04`)
- **Decisão consolidada (`docs/planejamento-tecnico.md` §12 e Passo 16):** o único banco de produção suportado na v1 é o **Oracle** (`OracleDialect`, `ojdbc11`, `flyway-database-oracle`, migrations `V1`–`V3` em paridade verificada com SQLite). Não há suporte nem dependências residuais de PostgreSQL.
- **[`PublicationConcurrencyIntegrationTest.java`](../../src/test/java/com/example/linkedinagent/service/PublicationConcurrencyIntegrationTest.java):**
  - Criado teste de integração multi-thread com barreiras `CountDownLatch` sobre banco SQLite real em arquivo temporário, validando três cenários críticos de corrida durante latência simulada da chamada externa ao LinkedIn:
    1. **Mesma `Idempotency-Key` em 6 threads simultâneas:** garante exatamente **1** chamada externa ao `LinkedInPublishingClient` e único registro `PUBLISHED`.
    2. **Chaves `Idempotency-Key` distintas em 6 threads simultâneas para o mesmo draft aprovado:** comprova que a reserva transacional `PUBLISHING` (`REQUIRES_NEW`) bloqueia todas as threads concorrentes com exceção controlada de domínio (`409` / `422`), resultando em exatamente **1** publicação externa.
    3. **Varreduras simultâneas do agendador (`processDueScheduledDrafts`) em 4 threads:** comprova que um draft `SCHEDULED` vencido é publicado no máximo **1** vez.
- **[`ProdProfileOracleRedisIntegrationTest.java`](../../src/test/java/com/example/linkedinagent/ProdProfileOracleRedisIntegrationTest.java):**
  - Atualizado para exigir as 3 migrations Oracle (`V1`–`V3`) e estendido com `oraclePreventsDuplicateExternalPostsUnderConcurrentSameKeyAndDifferentKeys()`, exercitando o `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`) e `uq_publication_fingerprint` diretamente contra o contêiner Oracle Free 23 + Redis 7.4 no pipeline CI.

### 2.2. Endurecimento do Pipeline CI/CD ([`.github/workflows/security-ci.yml`](../../.github/workflows/security-ci.yml))
- **Controle de concorrência (`concurrency`):** cancela execuções redundantes em `pull_request` do mesmo branch sem interromper execuções em `main`, `develop` ou agendadas (`schedule`).
- **Execução limpa (`mvn -B clean verify`):** executa toda a suíte unitária, de concorrência e de integração (incluindo Oracle + Redis via Testcontainers nos runners Ubuntu do GitHub Actions) sob JDK 21 (Temurin).
- **Cache condicional da NVD (`nvd-key`):** evita gravação desnecessária de caches vazios de dados do OWASP Dependency-Check quando o secret `NVD_API_KEY` ainda não estiver configurado, mantendo o alerta explícito no resumo do workflow e falha estrita em execuções `schedule` sem a chave.
- **Smoke test Docker ampliado (`docker-smoke`):** além de auditar `/app` contra vazamento de `.env`, chaves ou bancos `.db`/`.sqlite` e validar `/actuator/health`, verifica também a exposição funcional do contrato `/api-docs` no perfil `local` e remove o container ao final (`if: always()`).
- **Alinhamento de metadados de actions:** atualizado comentário da SHA pinada de `actions/setup-java` (`# v6.3.0`) para refletir a versão real atualizada pelo Dependabot.

### 2.3. Governança e Documentação de Portfólio
- **[`README.md`](../../README.md):** reestruturado para apresentação técnica de portfólio com badges de status CI/stack/licença, diagrama Mermaid da arquitetura em camadas DDD e fronteiras de segurança, diagrama de sequência cobrindo aprovação manual, agendamento automático (`ScheduledPublicationService`) e reserva idempotente em duas fases, passo a passo `curl` sanitizado e seção dedicada de CI/CD.
- **[`LICENSE`](../../LICENSE):** removidos colchetes residuais de template no nome do titular do copyright.
- **[`SECURITY.md`](../../SECURITY.md):** substituído placeholder pendente de canal de contato pelo fluxo privado *GitHub Private Vulnerability Reporting* (`security/advisories/new`) e consolidado o resumo de todos os controles técnicos vigentes (AES-256-GCM com AAD + `keyId`, HSTS, fail-safe `prod`, Lua Redis + HMAC-SHA256, LGPD).
- **[`CONTRIBUTING.md`](../../CONTRIBUTING.md):** atualizado para refletir a arquitetura em camadas DDD, paridade obrigatória de migrations `sqlite`/`oracle` e paridade OpenAPI.
- **[`docs/status-atual.md`](../status-atual.md), [`docs/planejamento-tecnico.md`](../planejamento-tecnico.md) e [`docs/reconciliacao-manual-publicacoes.md`](../reconciliacao-manual-publicacoes.md):** sincronizados com o encerramento do Passo 16 e com as regras de bloqueio de agendamento em tentativas `PUBLISHING`/`OUTCOME_UNKNOWN`.

---

## 3. Verificação executada

- `git diff --check`: zero erros de formatação ou marcadores de conflito.
- `mvn test-compile -o` e `mvn test -o`: compilação limpa (`--release 21`) e aprovação integral da suíte automatizada (**128 testes executados localmente + 3 testes Oracle/Redis Testcontainers habilitados automaticamente em ambientes com Docker como o GitHub Actions**, `0` falhas, `0` erros).
- Conformidade com `.gitleaks.toml`: todas as chaves fictícias de teste seguem o padrão `test-idempotency-key-*` sem segredos reais versionados.

---

## 4. Checklist externo pós-v1 (Operação / Deploy em Nuvem)

Com o código, os testes, os contêineres e o CI/CD da **v1** concluídos, restam apenas ações operacionais externas no momento da publicação em nuvem:
1. Configurar o secret `NVD_API_KEY` nas configurações do repositório no GitHub (*Settings → Secrets and variables → Actions*).
2. Configurar as variáveis de ambiente de produção (`DATABASE_URL`, `RATE_LIMIT_REDIS_URL`, `TOKEN_ENCRYPTION_KEY`, `RATE_LIMIT_HMAC_KEY`, `TRUSTED_PROXY_IPS`, `LINKEDIN_*`, `CORS_ALLOWED_ORIGINS`) no provedor de hospedagem e cadastrar a `LINKEDIN_REDIRECT_URI` HTTPS exata no LinkedIn Developer Portal.
3. Em ciclo futuro (v2), caso desejado, retomar o **Passo 11** (oportunidades locais e fontes autorizadas de vagas) ou suporte a upload de imagens via Images API.
