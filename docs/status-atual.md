# Status atual do projeto

**Atualizado em:** 30/10/2026
**Etapa atual:** Passo 10 em andamento — reserva durável, bloqueio de resultados ambíguos e contrato OpenAPI de publicação/histórico implementados; validação Oracle e rotina de reconciliação manual pendentes
**Referências:** [`docs/progress/STEP-10.md`](progress/STEP-10.md) e o [plano da próxima release](planejamento-tecnico.md#plano-proposto-para-a-próxima-release)

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
- Testes HTTP cobrem isolamento e paginação do histórico, falha persistida e replay impedido. O teste de colisão usa repositório simulado; concorrência no banco real continua sem validação.
- O OpenAPI local documenta o contrato completo de `POST /api/v1/drafts/{id}/publish` e `GET /api/v1/publications`: header `Idempotency-Key` obrigatório (1–200 caracteres), replay `201`, schema `ApiError` com exemplo para cada código emitido (`400`, `404`, `409` `PUBLICATION_ALREADY_PROCESSED`/`PUBLICATION_OUTCOME_UNKNOWN`, `422`, `429`, `502` `LINKEDIN_RESULT_UNKNOWN`/falha conhecida, `503`), respostas `401`/`403` sem corpo e enumeração dos estados `PUBLISHING`, `PUBLISHED`, `FAILED` e `OUTCOME_UNKNOWN`. Um teste de contrato verifica esses elementos no `/api-docs`.

## Limites funcionais atuais

- Apenas drafts textuais são aceitos; mídia e artigos são rejeitados.
- Não há endpoints de oportunidades.
- Falha conhecida do LinkedIn é registrada como `FAILED`. A mesma chave após falha retorna conflito; não há retry automático. Resultado externo ambíguo é salvo como `OUTCOME_UNKNOWN` e bloqueia qualquer nova chave para o draft. A reconciliação é manual; não há endpoint administrativo.
- Um registro `PUBLISHING` remanescente pode indicar chamada em curso ou interrupção do processo; novas tentativas são bloqueadas e é necessário reconciliar no LinkedIn antes de qualquer reenvio.
- A publicação é feita pelo fluxo de `POST /api/v1/drafts/{id}/publish`; não existe endpoint autônomo de publicações.
- O OpenAPI local tem o contrato de publicação e histórico revisado; os demais endpoints (auth, OAuth e drafts) ainda precisam da mesma revisão de schemas e erros como parte do Passo 12.
- A validação tardia de `TOKEN_ENCRYPTION_KEY` é intencional no perfil local para permitir iniciar a aplicação antes de configurar OAuth; inicie o fluxo somente após configurar a chave Base64 válida de 32 bytes. Logs e respostas não devem revelar o valor.

## Pendências e lacunas confirmadas

1. Executar testes de concorrência com a mesma chave e chaves diferentes, além de migrations e validação Hibernate, em Oracle descartável. Oracle foi confirmado como alvo, mas não há serviço ou teste Oracle no repositório atual.
2. Documentar uma rotina operacional segura de reconciliação manual para registros `PUBLISHING` e `OUTCOME_UNKNOWN`, sem retry automático. O contrato OpenAPI de publicação e histórico (schemas e respostas para `OUTCOME_UNKNOWN`, conflito e replay) foi concluído em 30/10/2026.
3. Revalidar a versão e o payload da Posts API em documentação oficial; a matriz registra verificação em 22/09/2026 e a configuração atual usa `Linkedin-Version: 202609`.
4. Validar com JDK 21 no ambiente-alvo e, antes de exposição pública, validar Redis compartilhado e o tratamento de IP de origem atrás do proxy confiável.
5. Definir e implementar o escopo local de oportunidades, se continuar desejado, depois do fechamento do fluxo de publicação.

Os itens 1–4 são trabalho de confiabilidade/validação e não devem ser descritos como concluídos. O item 5 depende de priorização de produto. A existência de entidades ou tabelas preliminares não significa que oportunidades estejam disponíveis.

## Plano proposto para a próxima release

**Objetivo de release proposto:** tornar o fluxo de publicação textual observável e confiável, sem ampliar para mídia ou oportunidades. Este recorte é uma proposta baseada nas lacunas imediatas do código, não uma decisão confirmada de produto.

1. **Fechar o contrato de idempotência e falhas.** A política implementada bloqueia reenvio após resultado ambíguo e exige reconciliação manual; validar sucesso, replay, falha conhecida e reserva `PUBLISHING` interrompida.
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
