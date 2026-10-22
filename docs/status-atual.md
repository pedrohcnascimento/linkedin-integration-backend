# Status atual do projeto

**Atualizado em:** 21/10/2026
**Etapa atual:** Passo 10 em andamento — fluxo textual e histórico implementados; validação de concorrência e recuperação pendentes
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
- Histórico `GET /api/v1/publications` é paginado, ordenado por `createdAt` e UUID descendentes, limitado ao usuário autenticado e a 50 itens por página. Inclui publicações com status `PUBLISHED` ou `FAILED`.
- Falhas conhecidas são registradas como `FAILED`; repetição da mesma chave retorna `409` sem nova chamada ao LinkedIn. Uma colisão de unicidade no registro inicial também é convertida para conflito antes da chamada externa.
- Testes HTTP cobrem isolamento e paginação do histórico, falha persistida e replay impedido. O teste de colisão usa repositório simulado; concorrência no banco real continua sem validação.

## Limites funcionais atuais

- Apenas drafts textuais são aceitos; mídia e artigos são rejeitados.
- Não há endpoints de oportunidades.
- Falha conhecida do LinkedIn é registrada como `FAILED`. A mesma chave após falha retorna conflito; não há retry automático. Resultado externo ambíguo (por exemplo, timeout depois de o LinkedIn aceitar a publicação) não tem reconciliação implementada.
- A publicação é feita pelo fluxo de `POST /api/v1/drafts/{id}/publish`; não existe endpoint autônomo de publicações.
- O OpenAPI local tem anotações nos controllers, mas a completude do contrato para todos os casos, erros e respostas ainda deve ser verificada como parte do Passo 12.
- A validação tardia de `TOKEN_ENCRYPTION_KEY` é intencional no perfil local para permitir iniciar a aplicação antes de configurar OAuth; inicie o fluxo somente após configurar a chave Base64 válida de 32 bytes. Logs e respostas não devem revelar o valor.

## Pendências e lacunas confirmadas

1. Definir recuperação segura após timeout/resultado externo ambíguo, sem arriscar posts duplicados.
2. Validar requisições concorrentes com a mesma chave de idempotência em banco real; teste atual apenas cobre a colisão única simulada.
3. Resolver a divergência de banco: `application-prod.yml` seleciona Oracle e há migration Oracle, enquanto o Maven também declara dependências PostgreSQL. Não há teste de integração PostgreSQL nem Oracle no código atual; a configuração de produção não foi comprovada contra um banco real de teste.
4. Revalidar a versão e o payload da Posts API em documentação oficial; a matriz registra verificação em 22/09/2026 e a configuração atual usa `Linkedin-Version: 202609`.
5. Validar com JDK 21 no ambiente-alvo e, antes de exposição pública, validar Redis compartilhado e o tratamento de IP de origem atrás do proxy confiável.
6. Definir e implementar o escopo local de oportunidades, se continuar desejado, depois do fechamento do fluxo de publicação.

Os itens 1–6 são trabalho de confiabilidade/validação e não devem ser descritos como concluídos. O item 7 depende de priorização de produto. A existência de entidades ou tabelas preliminares não significa que oportunidades estejam disponíveis.

## Plano proposto para a próxima release

**Objetivo de release proposto:** tornar o fluxo de publicação textual observável e confiável, sem ampliar para mídia ou oportunidades. Este recorte é uma proposta baseada nas lacunas imediatas do código, não uma decisão confirmada de produto.

1. **Fechar o contrato de idempotência e falhas.** Documentar respostas atuais; cobrir sucesso, replay, falha conhecida e timeout/resultado ambíguo. Decidir a regra de recuperação antes de implementar retry, considerando que não há garantia de exatamente-uma-vez entre o banco local e o LinkedIn.
2. **Confirmar o banco suportado.** Decidir se o destino é Oracle (o que `prod` configura hoje) ou PostgreSQL; estabelecer um banco descartável e teste reprodutível para migrations, validação Hibernate e operações de publicação. Se o destino for PostgreSQL, alinhar profile e migrations antes de declarar suporte.
3. **Endurecer concorrência e persistência.** Criar testes concorrentes para a mesma chave e chaves diferentes; verificar unicidade e estados parciais no banco escolhido. Não assumir compatibilidade multibanco sem executar a validação correspondente.
4. **Completar o contrato do histórico.** Verificar schemas, exemplos OpenAPI e comportamento de remoção do draft associado; o endpoint paginado e seus testes HTTP já estão implementados.
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
