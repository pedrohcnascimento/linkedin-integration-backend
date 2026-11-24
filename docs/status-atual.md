# Status atual do projeto

**Atualizado em:** 24/11/2026
**Etapa atual:** **Passo 16 — Revisão de portfólio e CI/CD concluído** (ciclo v1 encerrado; Passo 11 formalmente adiado para ciclo pós-v1)
**Referências:** [`docs/progress/STEP-16.md`](progress/STEP-16.md), [`docs/progress/STEP-15b.md`](progress/STEP-15b.md), [`docs/progress/STEP-15.md`](progress/STEP-15.md), [`docs/progress/STEP-14.md`](progress/STEP-14.md), [`docs/PLANO_AUDITORIA_SEGURANCA.md`](PLANO_AUDITORIA_SEGURANCA.md) e [`docs/reconciliacao-manual-publicacoes.md`](reconciliacao-manual-publicacoes.md)

Este documento consolida o estado vigente observado no código, na suíte automatizada e no pipeline CI/CD do projeto. Os relatórios em `docs/progress/` registram decisões e validações de cada etapa.

## Implementado e verificado na v1

- **Identidade local e segurança de sessão:** cadastro, login por sessão com rotação contra *session fixation*, consulta `/users/me`, logout (`204`), exclusão LGPD (`DELETE /api/v1/users/me`), proteção CSRF e timeouts de sessão (`30m` inatividade / `8h` absoluto).
- **OAuth 2.0 LinkedIn:** início autenticado, callback vinculado à sessão iniciadora com `state` de uso único (hash SHA-256), consulta sanitizada da conexão, desconexão local e cifragem AES-256-GCM em repouso com AAD por usuário e suporte a rotação de chaves (`keyId`).
- **Segurança de borda e Rate Limiting:** origens CORS HTTP/HTTPS exatas (rejeitando `*` e URLs com caminho), cabeçalhos HSTS (`prod`), `Referrer-Policy: no-referrer` e `Permissions-Policy`, limite de payload de `64 KB` e rate limiting fail-closed em memória (`local`/`test`) ou Redis atômico via Lua + HMAC-SHA256 (`prod`), respeitando `TRUSTED_PROXY_IPS`.
- **Persistência SQLite e Oracle:** migrations Flyway `V1`–`V3` em paridade para `sqlite` e `oracle` com Hibernate `ddl-auto=validate`, testadas em banco limpo, em upgrade (`V1 -> V3`) e contra contêineres reais Oracle Free 23 + Redis 7.4 via Testcontainers (`ProdProfileOracleRedisIntegrationTest`).
- **Fluxo completo de rascunhos, agendamento e publicação idempotente:** criação, listagem paginada, edição em `DRAFT`, aprovação manual ou com agendamento (`scheduledAt`), reagendamento (`PUT /api/v1/drafts/{id}/schedule`), cancelamento (`DELETE /api/v1/drafts/{id}/schedule`), publicação automática via `ScheduledPublicationService` (janela `PT60M` e estado `SCHEDULE_EXPIRED` fora da janela), publicação manual com `Idempotency-Key` e histórico paginado (`GET /api/v1/publications`).
- **Concorrência e prevenção de duplicatas:** reserva `PUBLISHING` em transação `REQUIRES_NEW` com lock pessimista antes da chamada externa; concorrência multi-thread validada para mesma chave, chaves distintas e varreduras simultâneas do agendador em `PublicationConcurrencyIntegrationTest` (SQLite) e `ProdProfileOracleRedisIntegrationTest` (Oracle). Resultados ambíguos entram em `OUTCOME_UNKNOWN` e bloqueiam novas tentativas até reconciliação manual.
- **Contrato OpenAPI e Observabilidade:** todas as 17 operações `/api/v1/**` verificadas automaticamente por `ApiDocumentationLocalProfileTest` (e ocultadas com `404` no perfil `prod`), correlação `X-Request-Id` no MDC e trilha `audit_event` sanitizada.
- **Conteinerização e CI/CD:** `Dockerfile` multi-stage não-root com `HEALTHCHECK`, `docker-compose.yml` e pipeline `.github/workflows/security-ci.yml` executando testes unitários/concorrência/Testcontainers, Docker smoke test, Gitleaks, CodeQL e OWASP Dependency-Check, além de Dependabot semanal.

## Limites funcionais deliberados da v1

- Apenas drafts textuais de membro são aceitos; mídia, artigos e páginas de organização não fazem parte da v1.
- O Passo 11 (oportunidades locais e fontes externas autorizadas de vagas) permanece formalmente adiado para ciclo futuro (ver [`docs/progress/STEP-11.md`](progress/STEP-11.md) e [`docs/planejamento-fontes-vagas.md`](planejamento-fontes-vagas.md)).
- Publicações agendadas exigem o processo backend em execução; atrasos além de `SCHEDULED_PUBLISH_GRACE_WINDOW` (`PT60M`) transicionam o draft para `SCHEDULE_EXPIRED` sem postar fora de hora.
- Reconciliação de tentativas `PUBLISHING` interrompidas ou `OUTCOME_UNKNOWN` permanece manual via banco segundo [`docs/reconciliacao-manual-publicacoes.md`](reconciliacao-manual-publicacoes.md), pois o LinkedIn não concede leitura de feed (`r_member_social`) a apps self-serve.

## Checklist operacional para deploy público / produção

Os itens abaixo dependem exclusivamente do ambiente externo de hospedagem (não de código pendente no repositório):

1. **Segredo `NVD_API_KEY` no GitHub:** cadastrar chave gratuita da NVD nos *Actions secrets* do repositório para habilitar a varredura completa do OWASP Dependency-Check nas execuções agendadas e PRs.
2. **Homologação de borda em nuvem (`prod`):** validar conectividade com a instância Oracle e o Redis gerenciado (TLS `rediss://`) do ambiente-alvo e configurar `TRUSTED_PROXY_IPS` com os CIDRs do reverse proxy/load balancer antes de abertura pública.
3. **Manutenção periódica da `Linkedin-Version`:** revisar semestralmente a versão configurada (`LINKEDIN_API_VERSION`, atualmente `202609`) frente ao calendário oficial de versões mensais da Posts API (suporte mínimo de 1 ano por versão).

## Precedência documental

1. **Estado vigente e checklist operacional:** este documento; o [`README.md`](../README.md) apresenta a visão geral de uso e arquitetura.
2. **Fatos e capacidades da API do LinkedIn:** [`docs/linkedin-capability-matrix.md`](linkedin-capability-matrix.md).
3. **Arquitetura e histórico de planejamento:** [`docs/planejamento-tecnico.md`](planejamento-tecnico.md).
4. **Registros históricos por etapa:** [`docs/progress/`](progress/).
