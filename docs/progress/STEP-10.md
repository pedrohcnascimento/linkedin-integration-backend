# STEP-10 — Rascunho aprovado e idempotência (entrega 1)

**Status:** em andamento. O fluxo criar → listar → editar → aprovar → publicar e o histórico paginado estão implementados. Esta continuação adiciona reserva transacional durável antes da chamada externa, bloqueio do draft durante a reserva e estado explícito para resultado ambíguo. A validação Oracle de concorrência continua pendente; o estado vigente está em [`../status-atual.md`](../status-atual.md).

## Implementado

A API possui `POST /api/v1/drafts`, `GET /api/v1/drafts`, `PATCH /api/v1/drafts/{id}`, `POST /api/v1/drafts/{id}/approve` e `POST /api/v1/drafts/{id}/publish`. O histórico está disponível em `GET /api/v1/publications?page=0&size=20`; não há consulta individual `GET /api/v1/drafts/{id}`. O usuário autenticado só consegue acessar os próprios rascunhos e publicações. A publicação exige estado `APPROVED`, conexão LinkedIn ativa e o header `Idempotency-Key`.

O serviço confirma a reserva `PUBLISHING` em uma transação antes de chamar a porta `LinkedInPublishingPort`. Em sucesso, salva o `x-restli-id`, marca a publicação como `PUBLISHED` e o rascunho como `PUBLISHED`. Em falha conhecida do provedor, registra `FAILED` e um código interno sem devolver detalhes externos.

A impressão digital combina usuário, rascunho, instante de aprovação e chave de idempotência. Uma repetição da mesma operação depois de publicação concluída devolve a publicação já salva, não chama novamente o LinkedIn e responde `201` (o mesmo status da primeira chamada). A restrição única já existente em `publication` também protege a persistência. Isso não demonstra, por si só, tratamento seguro de chamadas concorrentes.

Falha conhecida do provedor é persistida com status `FAILED` e código interno. Uma repetição com a mesma chave após essa falha retorna conflito; não há retry automático. Falha de transporte, resposta HTTP 408/409/5xx ou resposta 201 sem `x-restli-id` pode significar que o LinkedIn aceitou o post: fica como `OUTCOME_UNKNOWN`, e nenhuma chave nova pode publicar o mesmo draft até reconciliação manual.

Se o processo for interrompido antes de finalizar, a reserva `PUBLISHING` continua persistida e bloqueia novas tentativas. Ela deve ser reconciliada manualmente; não há reenvio automático. A reserva usa lock pessimista no draft para serializar tentativas concorrentes, mas esse comportamento precisa ser comprovado no Oracle escolhido para produção.

O histórico de publicações é paginado, ordenado por `createdAt` descendente e UUID descendente para desempate, com tamanho máximo de 50. Inclui estados `PUBLISHED` e `FAILED`; o ID do draft associado pode ser nulo se o draft tiver sido removido no banco.

Uma violação de integridade ao inserir a tentativa `PUBLISHING` é mapeada para `409 PUBLICATION_ALREADY_PROCESSED`, antes de chamar o LinkedIn. O teste automatizado simula a colisão no repositório; ainda é necessário provar concorrência no banco real escolhido.

A entrega inicial aceita somente rascunhos textuais. Conteúdo de artigo, imagem, vídeo e outros tipos permanecem fora do escopo até haver adaptadores e contratos próprios.

A listagem `GET /api/v1/drafts?page=0&size=20` é paginada, ordenada pelo `updatedAt` descendente e limitada ao usuário autenticado. O tamanho máximo de página é 50. A edição `PATCH /api/v1/drafts/{id}` aceita atualização parcial de texto e título; somente drafts em `DRAFT` podem ser editados, preservando a fingerprint após aprovação.

## Validação registrada anteriormente

A entrega inicial passou com 38 testes. Na continuação do Passo 10, `mvn test` passou com 55 testes, sem falhas nem skips. Os testes HTTP atuais cobrem publicação idempotente, pré-condição de aprovação, exigência do header, listagem/edição, histórico, falha persistida, ausência de retry após falha conhecida e isolamento por proprietário. A colisão concorrente é coberta em teste unitário por exceção simulada, não por concorrência em banco real.

## Continuação de 22/10/2026

- `DraftWorkflowService` separa a reserva, a chamada externa e a conclusão em transações distintas, evitando que a reserva desapareça por rollback normal após queda do processo.
- A porta/adaptador distinguem falha conhecida de resultado incerto; erros de transporte, respostas HTTP 408/409/5xx e sucesso sem identificador são tratados como ambíguos.
- A publicação ambígua e as reservas `PUBLISHING` bloqueiam novas chaves para o mesmo draft; o HTTP retorna conflito para tentativas e `502 LINKEDIN_RESULT_UNKNOWN` para o resultado ambíguo inicial.
- Foram adicionados testes de estado/retry ambíguo, erros HTTP 409/5xx e contrato OpenAPI. `mvn clean test` passou com JDK 21, totalizando 58 testes sem falhas, erros ou skips.

## Continuação de 30/10/2026 — contrato OpenAPI de publicação e histórico

**Objetivo:** fechar a pendência 2 (revisão OpenAPI para `OUTCOME_UNKNOWN`, conflito e replay) sem alterar comportamento de runtime.

**Alterações:**
- `DraftController`: `POST /api/v1/drafts/{id}/publish` passou a documentar o contrato de idempotência na descrição da operação e todas as respostas emitidas pelo fluxo — `201` (incluindo replay, com schema `PublicationResponse`), `400 IDEMPOTENCY_KEY_REQUIRED`, `401`/`403` sem corpo, `404 DRAFT_NOT_FOUND`, `409 PUBLICATION_ALREADY_PROCESSED`/`PUBLICATION_OUTCOME_UNKNOWN`, `422 DRAFT_NOT_APPROVED`/`LINKEDIN_CONNECTION_REQUIRED`, `429 RATE_LIMITED`/`LINKEDIN_RATE_LIMITED`, `502 LINKEDIN_RESULT_UNKNOWN`/falha conhecida e `503 RATE_LIMIT_UNAVAILABLE`, cada uma com schema `ApiError` e exemplo.
- O header `Idempotency-Key` aparece como obrigatório, com `minLength 1` e `maxLength 200`, os mesmos limites do serviço. O binding continua `required = false` para preservar o erro `400` estável do próprio serviço.
- `GET /api/v1/publications`: schema `PublicationPageResponse`, exemplo com `OUTCOME_UNKNOWN` e `PUBLISHED`, `400 INVALID_PAGINATION` e `401`.
- `PublicationResponse` documenta `status` como enumeração (`PUBLISHING`, `PUBLISHED`, `FAILED`, `OUTCOME_UNKNOWN`) com o significado operacional de cada estado; `draftId`, `externalPostId`, `failureCode` e `publishedAt` aparecem como anuláveis (OpenAPI 3.1).
- `ApiDocumentationLocalProfileTest`: novo teste `openApiDocumentsThePublicationIdempotencyAndFailureContract` verifica esses elementos no `/api-docs`.

**Decisões:** nenhum status HTTP ou código de erro foi alterado; o replay concluído continua respondendo `201`. A documentação descreve o comportamento já coberto pelos testes HTTP.

**Validação:** `mvn -o clean test` passou com 59 testes (58 anteriores + 1 novo), sem falhas, erros ou skips, usando o JBR 25 do IntelliJ (não há JDK 21 no PATH desta máquina). A validação com JDK 21 continua pendente no item de release. Observação de ambiente: a auto-anexação do Mockito (inline mock maker) trava dentro de ambientes de execução isolados; fora deles a suíte roda normalmente.

## Continuação de 30/10/2026 (entrega 2) — procedimento operacional de reconciliação manual

**Objetivo:** fechar a pendência 3 (documentar rotina operacional segura de reconciliação manual para `PUBLISHING` e `OUTCOME_UNKNOWN`, sem retry automático) e validar por teste automatizado que as transições de estado documentadas preservam todas as invariantes de idempotência e histórico.

**Alterações:**
- Criado [`docs/reconciliacao-manual-publicacoes.md`](../reconciliacao-manual-publicacoes.md) com:
  - racional técnico (ausência de `r_member_social`, ausência de endpoint administrativo na v1 e proibição de retry automático);
  - janela mínima de segurança antes de tocar em `PUBLISHING` (mínimo de 2 minutos acima do timeout HTTP `5s`/`10s` + ausência de requisição ativa) e janela de propagação no perfil LinkedIn;
  - proibições explícitas (`DELETE` em `publication`, alteração de `content_draft.approved_at`, formatos de data fora de ISO-8601 UTC com `Z` exigidos por `InstantStringConverter`, ou dados sensíveis em `audit_event`);
  - consultas de diagnóstico, scripts transacionais SQLite (`local`) e Oracle (`prod`) para o **Cenário A** (confirmado no perfil → `PUBLISHED`, limpando `failure_code` e marcando `content_draft` como `PUBLISHED`) e o **Cenário B** (ausente no perfil → `FAILED` com `failure_code = 'RECONCILED_NOT_PUBLISHED'`, mantendo `content_draft` em `APPROVED` sem reenvio automático), ambos gravando trilha sanitizada em `audit_event`;
  - consultas e checagens de verificação pós-reconciliação.
- `DraftController`: ajustada a descrição de `PublicationResponse` no OpenAPI para contemplar `RECONCILED_NOT_PUBLISHED` em `failureCode` e identificadores registrados via reconciliação manual.
- `DraftControllerIntegrationTest`: adicionados dois testes de integração (`manualReconciliationAsPublishedAllowsIdempotentReplayAndPreventsDuplicatePost` e `manualReconciliationAsNotPublishedKeepsOldKeyBlockedAndAllowsFreshIdempotencyKey`), cobrindo também reserva `PUBLISHING` interrompida por falha abrupta, leitura via `AuditEventRepository` (`InstantStringConverter`), bloqueio da chave antiga e liberação controlada para nova `Idempotency-Key`.

**Validação:** `mvn -o -B test` passou com 61 testes (59 anteriores + 2 novos), sem falhas, erros ou skips, usando JBR 25.

## Pendências remanescentes

1. Executar migrations, `ddl-auto=validate` e testes de concorrência em Oracle descartável para mesma chave, chaves distintas, reservas interrompidas e lock pessimista. Bloqueado no ambiente atual pela ausência de Docker/instância Oracle descartável.
2. ~~Completar a revisão OpenAPI com schemas e exemplos para `OUTCOME_UNKNOWN`, respostas de conflito e replay.~~ Concluído em 30/10/2026.
3. ~~Documentar uma rotina operacional segura de reconciliação manual; não oferecer retry automático.~~ Concluído em 30/10/2026 ([`docs/reconciliacao-manual-publicacoes.md`](../reconciliacao-manual-publicacoes.md)).
4. O replay concluído responde `201` hoje. O contrato está documentado no OpenAPI e só deve ser alterado com decisão explícita e teste de compatibilidade.
