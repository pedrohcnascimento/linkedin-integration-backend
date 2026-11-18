# STEP-11 — Oportunidades locais e fontes externas de vagas (adiado)

**Status:** adiado por decisão de produto em 30/10/2026. Esta etapa **não** será implementada no ciclo atual; o desenvolvimento segue diretamente para o **Passo 12** (finalização da API REST e do contrato OpenAPI) e etapas subsequentes de estabilização.

## Racional do adiamento

1. **Foco na confiabilidade da v1 de publicação textual:** o objetivo prioritário da release atual é entregar o ciclo OAuth + rascunhos + aprovação + publicação idempotente + histórico + contrato OpenAPI + testes/segurança sem abrir novas frentes funcionais.
2. **Pré-requisitos próprios das fontes externas de vagas:** conforme detalhado em [`docs/planejamento-fontes-vagas.md`](../planejamento-fontes-vagas.md), a listagem/importação de vagas sem scraping depende de curadoria inicial de quadros autorizados (Greenhouse, Lever, Arbeitnow e avaliação controlada do JSearch Basic), revisão específica dos termos de exibição/atribuição de cada provedor, deduplicação e métricas comparativas de cobertura no Brasil.
3. **Isolamento seguro no código atual:** embora existam artefatos preliminares de banco/persistência (`opportunity` nas migrations V1, `OpportunityEntity` e `OpportunityRepository`) e pacotes reservados (`domain.opportunity`, `application.opportunity`), **nenhum endpoint, caso de uso ou adaptador externo de oportunidades está ativo ou exposto**. Esses artefatos permanecem dormentes até que esta etapa seja retomada.

## Quando retomar

- Somente após concluir a estabilização do fluxo atual (Passos 12–14 e validações de release pendentes em [`docs/status-atual.md`](../status-atual.md)).
- Ao retomar, seguir as fases e limites definidos em [`docs/planejamento-fontes-vagas.md`](../planejamento-fontes-vagas.md) (iniciando pela Fase 1 gratuita: Greenhouse + Lever + Arbeitnow, mantendo proibição absoluta de scraping ou automação no LinkedIn).
