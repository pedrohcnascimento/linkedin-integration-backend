# STEP-02 — Validar a API e congelar o escopo da v1

**Objetivo:** transformar a pesquisa da API em decisões implementáveis, sem gerar código.

## Ações realizadas

- Pesquisa web (22/09/2026) nas fontes oficiais (Microsoft Learn) e em fontes secundárias qualificadas para confirmar o estado atual de:
  - Sign In with LinkedIn usando OpenID Connect (identidade)
  - Share on LinkedIn / `w_member_social` (publicação)
  - Fluxo OAuth 2.0, validade de access token e disponibilidade de refresh token
  - Limites de uso conhecidos
- Criado `docs/linkedin-capability-matrix.md` com endpoint, headers, corpo, permissão e fonte para cada chamada planejada

## Divergência encontrada (não resolvida por conta própria)

O planejamento original especifica `POST /v2/ugcPosts` (UGC Post API). A pesquisa mostra que essa API é **legada** desde 30/06/2023, substituída pela **Posts API** (`POST /rest/posts`, versionada por header `Linkedin-Version`). Ambas tecnicamente funcionam hoje para publicação no próprio perfil, mas só a Posts API é ativamente mantida.

Conforme a regra do próprio planejamento ("se a documentação encontrada contradizer este plano, não escolher silenciosamente"), **registrei a divergência e solicitei decisão** em vez de trocar o endpoint unilateralmente. Ver seção "Decisão pendente" em `docs/linkedin-capability-matrix.md`.

## Outras confirmações relevantes

- Access token: 60 dias de validade, confirmado em fonte oficial
- Refresh token: **não deve ser presumido** para apps self-serve comuns — exige aprovação como parceiro do Marketing Developer Platform para "programmatic refresh tokens". Isso **confirma** (não contradiz) a cautela que já estava na seção 5.2 do planejamento original
- Identidade do membro: via `GET /v2/userinfo` (OpenID Connect), campo `sub`, não mais via `/v2/me` com `r_liteprofile` (descontinuado para apps novos desde 01/08/2023)
- Limites numéricos de rate limit para publicação não têm confirmação em fonte primária — tratamento defensivo de `429` será implementado sem presumir um número exato

## Testes

Não aplicável (etapa documental).

## Riscos / pendências

- **Bloqueador para o Passo 9:** aguardando decisão sobre Posts API vs. UGC Post API antes de implementar o adaptador de publicação. Não bloqueia os Passos 3 a 8.
- A matriz deve ser revalidada perto do Passo 9, já que o LinkedIn publica uma nova versão da Posts API todo mês

## Próximo passo

**Passo 3 — Registrar o aplicativo no LinkedIn sem expor segredos**: criar o app no Developer Portal, habilitar os produtos "Sign In with LinkedIn using OpenID Connect" e "Share on LinkedIn", configurar redirect URI local, documentar variáveis de ambiente (sem valores reais). Esta etapa depende de uma ação manual sua no Developer Portal do LinkedIn — vou te guiar passo a passo quando chegarmos lá.
