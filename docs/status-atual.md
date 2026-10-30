# Status atual do projeto

**Atualizado em:** 30/10/2026
**Etapa atual:** Passo 12 em andamento (entrega 1) — Passo 11 adiado para ciclo posterior; contrato OpenAPI de autenticação/sessão/logout e publicação/histórico concluído; falta revisar OAuth e CRUD/aprovação de drafts (entrega 2), além da validação Oracle do Passo 10
**Referências:** [`docs/progress/STEP-12.md`](progress/STEP-12.md), [`docs/progress/STEP-11.md`](progress/STEP-11.md), [`docs/progress/STEP-10.md`](progress/STEP-10.md), [`docs/reconciliacao-manual-publicacoes.md`](reconciliacao-manual-publicacoes.md) e o [plano da próxima release](planejamento-tecnico.md#plano-proposto-para-a-próxima-release)

Este documento consolida o estado vigente observado no código e na configuração do projeto. Os relatórios em `docs/progress/` registram decisões e validações do momento em que foram escritos; não são a fonte para pendências atuais.

## Implementado

- Cadastro de usuários locais, autenticação por sessão, consulta do usuário autenticado, logout e proteção CSRF.
- OAuth LinkedIn: início, callback com `state` de uso único, consulta sanitizada da conexão, desconexão local e tokens cifrados em repouso. A documentação existente registra smoke test com o aplicativo do Developer Portal (`ACTIVE`); a suíte automatizada simula o provedor.
- No perfil local, a chave de cifragem é validada ao iniciar OAuth, não no startup da aplicação. O `state` é consumido antes das chamadas externas; falhas posteriores exigem iniciar novo fluxo.
- Origens CORS são normalizadas com trim e validadas como origens HTTP/HTTPS exatas; wildcard e URLs que não sejam origem são rejeitados.
- Rate limiting de autenticação, OAuth e escrita/publicação de drafts.
- Persistência local SQLite e perfil `prod` configurado para Oracle, com migrations Flyway específicas e Hibernate em `ddl-auto=validate`.
- Adaptador de publicação textual via Posts API, configurável por versão, e fluxo de drafts com criação, listagem paginada, edição antes de aprovação, aprovação e publicação autenticada.
- Publicação exige `Idempotency-Key`; replay de publicação já concluída retorna a publicação existente sem chamar novamente o LinkedIn. O contrato HTTP atual responde `201` também no replay.
- Histórico `GET /api/v1/publications` é paginado, ordenado por `createdAt` e UUID descendentes, limitado ao usuário autenticado e a 50 itens por página. Pode incluir publicações `PUBLISHED`, `FAILED`, `PUBLISHING` ou `OUTCOME_UNKNOWN`.
- Falhas conhecidas são registradas como `FAILED`; repetição da mesma chave retorna `409` sem nova chamada ao LinkedIn. Uma colisão de unicidade no registro inicial também é convertida para conflito antes da chamada externa.
- A reserva `PUBLISHING` é confirmada em transação antes da chamada ao LinkedIn. Ela continua bloqueando tentativas se o processo cair antes de finalizar.
- Falha de transporte, respostas HTTP 408/409/5xx ou resposta de sucesso sem `x-restli-id` são tratadas como `OUTCOME_UNKNOWN`; a mesma chave e novas chaves para o mesmo draft são bloqueadas até reconciliação manual.
- A reserva usa lock pessimista no draft para impedir que chaves diferentes iniciem publicação concorrente do mesmo draft.
- Testes HTTP cobrem isolamento e paginação do histórico, falha persistida, reserva `PUBLISHING` interrompida, reconciliação manual (tanto para `PUBLISHED` quanto para `FAILED`/`RECONCILED_NOT_PUBLISHED`, com trilha em `audit_event`) e replay impedido. O teste de colisão usa repositório simulado; concorrência no banco real continua sem validação.
- O OpenAPI local documenta o contrato completo de `POST /api/v1/drafts/{id}/publish`, `GET /api/v1/publications` e da fatia de autenticação/sessão (`GET /api/v1/auth/csrf`, `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/logout` via `OpenApiConfig`, `GET /api/v1/users/me`), usando um único record `ApiError` compartilhado e mantendo `/api-docs` e `/swagger-ui/**` explicitamente desativados no perfil `prod`. Testes de contrato verificam esses elementos.

## Limites funcionais atuais

- Apenas drafts textuais são aceitos; mídia e artigos são rejeitados.
- Não há endpoints de oportunidades; o Passo 11 foi formalmente adiado para implementação posterior à estabilização da v1 (ver [`docs/progress/STEP-11.md`](progress/STEP-11.md)).
- Falha conhecida do LinkedIn é registrada como `FAILED`. A mesma chave após falha retorna conflito; não há retry automático. Resultado externo ambíguo é salvo como `OUTCOME_UNKNOWN` e bloqueia qualquer nova chave para o draft. A reconciliação é manual (ver [`docs/reconciliacao-manual-publicacoes.md`](reconciliacao-manual-publicacoes.md)); não há endpoint administrativo.
- Um registro `PUBLISHING` remanescente pode indicar chamada em curso ou interrupção do processo; novas tentativas são bloqueadas e é necessário seguir o [procedimento de reconciliação manual](reconciliacao-manual-publicacoes.md) após conferir o perfil no LinkedIn antes de qualquer reenvio.
- A publicação é feita pelo fluxo de `POST /api/v1/drafts/{id}/publish`; não existe endpoint autônomo de publicações.
- O OpenAPI local já tem os contratos de autenticação/sessão/logout, publicação e histórico revisados; os endpoints de `LinkedInOAuthController` e os demais endpoints de `DraftController` (criação, listagem, edição e aprovação) serão concluídos na Entrega 2 do Passo 12.
- A validação tardia de `TOKEN_ENCRYPTION_KEY` é intencional no perfil local para permitir iniciar a aplicação antes de configurar OAuth; inicie o fluxo somente após configurar a chave Base64 válida de 32 bytes. Logs e respostas não devem revelar o valor.

## Pendências e lacunas confirmadas

1. Executar testes de concorrência com a mesma chave e chaves diferentes, além de migrations e validação Hibernate, em Oracle descartável. Oracle foi confirmado como alvo, mas não há serviço ou teste Oracle no repositório atual.
2. Concluir a Entrega 2 do Passo 12: completar schemas, headers e exemplos OpenAPI em `LinkedInOAuthController` e nos endpoints de criação/listagem/edição/aprovação de `DraftController`, além de teste de paridade de rotas (ver [`docs/progress/STEP-12.md`](progress/STEP-12.md)). A Entrega 1 (autenticação/sessão/logout, unificação de `ApiError`, rate limit de publicação no `WebConfig` e desativação raiz do `springdoc` em `prod`) e o procedimento de reconciliação manual ([`docs/reconciliacao-manual-publicacoes.md`](reconciliacao-manual-publicacoes.md)) foram concluídos em 30/10/2026.
3. Revalidar a versão e o payload da Posts API em documentação oficial; a matriz registra verificação em 22/09/2026 e a configuração atual usa `Linkedin-Version: 202609`.
4. Validar com JDK 21 no ambiente-alvo e, antes de exposição pública, validar Redis compartilhado e o tratamento de IP de origem atrás do proxy confiável.
5. **Adiado para ciclo posterior (Passo 11):** oportunidades locais e integração com fontes externas autorizadas de vagas não serão feitas neste momento e ficam pós-estabilização da release atual (ver [`docs/progress/STEP-11.md`](progress/STEP-11.md) e [`docs/planejamento-fontes-vagas.md`](planejamento-fontes-vagas.md)).

Os itens 1–4 são trabalho de confiabilidade/validação e não devem ser descritos como concluídos. O item 5 depende de priorização de produto. A existência de entidades ou tabelas preliminares não significa que oportunidades estejam disponíveis.

## Plano proposto para a próxima release

**Objetivo de release proposto:** tornar o fluxo de publicação textual observável e confiável, sem ampliar para mídia ou oportunidades. Este recorte é uma proposta baseada nas lacunas imediatas do código, não uma decisão confirmada de produto.

1. **Fechar o contrato de idempotência e falhas.** Concluído em SQLite/MockMvc em 30/10/2026: política implementada e testada para sucesso, replay, falha conhecida, resultado ambíguo (`OUTCOME_UNKNOWN`), reserva `PUBLISHING` interrompida e reconciliação manual documentada em [`docs/reconciliacao-manual-publicacoes.md`](reconciliacao-manual-publicacoes.md). Resta a validação equivalente em Oracle (itens 2 e 3).
2. **Validar o banco suportado.** Oracle é o destino confirmado para `prod`; executar migrations, validação Hibernate e operações de publicação em schema descartável. As dependências PostgreSQL não significam suporte PostgreSQL.
3. **Demonstrar concorrência e persistência.** Criar testes concorrentes para a mesma chave e chaves diferentes em Oracle; verificar lock pessimista, unicidade e estados parciais.
4. **Completar o contrato do histórico.** Concluído no OpenAPI em 30/10/2026: schemas, exemplos, `OUTCOME_UNKNOWN` e `draftId` nulo após remoção do draft estão documentados e cobertos por teste de contrato.
5. **Executar a verificação de release.** Rodar build e suíte completa com JDK 21, revalidar a versão/payload da Posts API em documentação oficial, verificar OpenAPI, rate limiting/Redis e origem de proxy no ambiente suportado; atualizar README, limitações e instruções com resultados reproduzíveis.

**Fora deste recorte:** uploads de mídia/artigos, oportunidades, Docker e automações de candidatura. Só incluir após decisão explícita e atualização do escopo da release.

## Precedência documental

1. **Estado vigente e pendências:** este documento; o README resume o estado para usuários.
2. **Fatos e capacidades do LinkedIn:** [`docs/linkedin-capability-matrix.md`](linkedin-capability-matrix.md).
3. **Arquitetura e requisitos planejados:** [`docs/planejamento-tecnico.md`](planejamento-tecnico.md); o planejamento não comprova implementação.
4. **Histórico de execução:** [`docs/progress/`](progress/).

## Decisões anteriores resolvidas

- A pendência do titular da licença MIT foi resolvida; ver `LICENSE`.
- A decisão é usar Posts API (`POST /rest/posts`), em vez da UGC Post API legada; ver [`docs/linkedin-capability-matrix.md`](linkedin-capability-matrix.md).
