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

## Pendências remanescentes

1. Executar migrations, `ddl-auto=validate` e testes de concorrência em Oracle descartável para mesma chave, chaves distintas, reservas interrompidas e lock pessimista.
2. Completar a revisão OpenAPI com schemas e exemplos para `OUTCOME_UNKNOWN`, respostas de conflito e replay.
3. Documentar uma rotina operacional segura de reconciliação manual; não oferecer retry automático.
4. O replay concluído responde `201` hoje. O contrato deve ser documentado e só alterado com decisão explícita e teste de compatibilidade.
