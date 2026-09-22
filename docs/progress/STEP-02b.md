# STEP-02b — Decisão: Posts API vs. UGC Post API

**Contexto:** o Passo 2 identificou uma divergência entre o planejamento original (`POST /v2/ugcPosts`) e o estado atual da documentação oficial do LinkedIn (Posts API como superfície ativamente mantida). Conforme a regra do projeto, a decisão foi levada ao proprietário em vez de resolvida unilateralmente.

## Decisão

**Posts API (`POST /rest/posts`)** — confirmada pelo proprietário em 22/09/2026.

## Alterações feitas

- `docs/linkedin-capability-matrix.md`: seção "Decisão pendente" substituída por "Decisão registrada", com as consequências práticas para o Passo 9 (formato de corpo, headers obrigatórios, onde a versão deve ficar configurada, mapeamento de `externalPostId`)
- `docs/planejamento-tecnico.md`: adicionado um aviso no topo do documento apontando a atualização e deixando explícito que a matriz de capacidades prevalece sobre o documento original em caso de conflito factual sobre a API do LinkedIn

## Impacto

Nenhum código foi escrito ainda, então não há refatoração necessária. Esta decisão vincula o Passo 9 (adaptador de publicação): `LinkedInPublicationCommand`, o mapeador de saída e os testes com WireMock/MockWebServer devem ser desenhados para o formato da Posts API desde o início.

## Próximo passo

Passo 3 — Registrar o aplicativo no LinkedIn sem expor segredos.
