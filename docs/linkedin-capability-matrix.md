# Matriz de capacidades — API do LinkedIn (v1)

**Última verificação:** 22/09/2026, via busca web (Microsoft Learn / LinkedIn Developer docs e fontes secundárias qualificadas, citadas por link)
**Responsável pela verificação:** agente de IA (Passo 2 do roteiro); revalidar a documentação oficial antes do Passo 9

> Esta matriz é o contrato de escopo da v1. Nenhuma chamada fora daqui deve ser implementada sem atualizar este arquivo primeiro.

## Contexto histórico: divergência com o planejamento original

O `docs/planejamento-tecnico.md` (seção 3.1) especifica `POST https://api.linkedin.com/v2/ugcPosts` como endpoint de publicação. Esse endpoint é a **API legada** (UGC Post API); a **Posts API** (`POST https://api.linkedin.com/rest/posts`) é a superfície ativamente mantida. A divergência foi resolvida: a decisão vigente, confirmada pelo proprietário em 22/09/2026, é usar Posts API, conforme a seção "Decisão registrada" ao final deste documento. Esta introdução preserva o contexto da análise, não indica uma pendência atual. Fontes verificadas indicam que:

- A Posts API é versionada por data (header `Linkedin-Version: YYYYMM`) e é a superfície **ativamente mantida**
- A documentação de consumidor do "Share on LinkedIn" ainda demonstra o endpoint antigo `/v2/ugcPosts` e ele **ainda funciona** para publicação no próprio perfil, segundo fontes secundárias — mas não há garantia de manutenção contínua
- O formato do corpo da requisição é **diferente** entre as duas: a UGC Post API usa `specificContent."com.linkedin.ugc.ShareContent".shareCommentary.text`; a Posts API usa um corpo plano com `commentary`, `visibility`, `distribution`, `lifecycleState`

O registro detalhado da decisão e suas consequências está na seção "Decisão registrada" ao final deste documento.

## 1. Identidade e login (Sign In with LinkedIn usando OpenID Connect)

| Item | Valor |
|---|---|
| Produto no Developer Portal | Sign In with LinkedIn using OpenID Connect |
| Escopos | `openid`, `profile` (obrigatórios); `email` (opcional, só se o app precisar do e-mail) |
| Aprovação | Nenhuma — self-serve |
| Autorização | `GET https://www.linkedin.com/oauth/v2/authorization` (3-legged / Authorization Code flow) |
| Troca de código | `POST https://www.linkedin.com/oauth/v2/accessToken` |
| Dados de identidade | `GET https://api.linkedin.com/v2/userinfo` → retorna `sub` (identificador do membro), `given_name`, `family_name`, `picture`, `locale`, e `email`/`email_verified` se o escopo `email` foi concedido |
| Construção do URN | `urn:li:person:{sub}` |
| Observação | Escopos legados `r_liteprofile`/`r_emailaddress` foram descontinuados para apps novos desde 01/08/2023 — não usar |

## 2. Publicação (Share on LinkedIn)

| Item | Valor |
|---|---|
| Produto no Developer Portal | Share on LinkedIn |
| Escopo | `w_member_social` |
| Aprovação | Nenhuma — permissão aberta (self-serve), liberada ao adicionar o produto |
| Finalidade documentada do escopo | "Post, comment, and like posts on behalf of an authenticated member" — a v1 deste projeto usa **apenas a criação de posts**; curtir/comentar programaticamente fica fora do escopo por decisão própria (seção 2.2 do planejamento) |

### 2.1 Opção A — Posts API (recomendada, ativamente mantida)

| Item | Valor |
|---|---|
| Endpoint | `POST https://api.linkedin.com/rest/posts` |
| Headers obrigatórios | `Authorization: Bearer {token}`, `Content-Type: application/json`, `X-Restli-Protocol-Version: 2.0.0`, `Linkedin-Version: {YYYYMM}` |
| Corpo (texto) | `{"author": "urn:li:person:{sub}", "commentary": "...", "visibility": "PUBLIC" \| "CONNECTIONS", "distribution": {"feedDistribution": "MAIN_FEED", "targetEntities": [], "thirdPartyDistributionChannels": []}, "lifecycleState": "PUBLISHED", "isReshareDisabledByAuthor": false}` |
| Corpo (URL/artigo) | Igual ao texto, acrescentando `content.article` com `source` (URL), `title`, `description` |
| Corpo (imagem) | Requer upload prévio via Images API (`initializeUpload`) para obter `urn:li:image:{id}`, referenciado em `content.media` |
| Resposta de sucesso | `201 Created`, sem corpo; ID do post no header `x-restli-id` (ex.: `urn:li:share:...`) |
| Versionamento | Nova versão mensal; cada versão suportada por no mínimo 1 ano. Fixar a versão em configuração, não hardcoded espalhado pelo código |

### 2.2 Opção B — UGC Post API (legada, como no planejamento original)

| Item | Valor |
|---|---|
| Endpoint | `POST https://api.linkedin.com/v2/ugcPosts` |
| Headers obrigatórios | `Authorization: Bearer {token}`, `Content-Type: application/json`, `X-Restli-Protocol-Version: 2.0.0` (sem header de versão por data) |
| Corpo (texto) | `{"author": "urn:li:person:{sub}", "lifecycleState": "PUBLISHED", "specificContent": {"com.linkedin.ugc.ShareContent": {"shareCommentary": {"text": "..."}, "shareMediaCategory": "NONE"}}, "visibility": {"com.linkedin.ugc.MemberNetworkVisibility": "PUBLIC"}}` |
| Corpo (URL/artigo) | Igual, com `shareMediaCategory: "ARTICLE"` e `media: [{"status": "READY", "originalUrl": "...", "title": {...}, "description": {...}}]` |
| Status | Legada desde 30/06/2023; sem indicação oficial de desativação imediata, mas sem novos recursos |

## 3. Tokens

| Item | Valor confirmado |
|---|---|
| Access token — validade | **60 dias** (fixo, todos os apps) |
| Refresh token — disponibilidade | **Não presumir.** Para apps self-serve comuns, "programmatic refresh tokens" exigem aprovação como parceiro do Marketing Developer Platform. Sem essa aprovação, a resposta de token pode não incluir `refresh_token` |
| Refresh token — validade (quando concedido) | 365 dias a partir da emissão; usar o refresh token **não** estende esse prazo |
| Comportamento sem refresh token | Ao expirar o access token (60 dias), a aplicação deve marcar a autorização como `EXPIRED` e solicitar novo fluxo OAuth completo ao usuário — exatamente como já previsto na seção 5.2 do planejamento |
| Ação obrigatória na implementação (Passo 8) | Inspecionar a resposta real de `POST /oauth/v2/accessToken` no ambiente de desenvolvimento e registrar se `refresh_token` veio presente, antes de programar qualquer lógica de refresh |

## 4. Limites de uso

| Item | Status |
|---|---|
| Limite de chamadas de publicação por membro/dia | Mencionado por fontes secundárias (não oficiais) como próximo de 100/dia — **não confirmado em fonte primária**. Implementar tratamento de `429` de forma defensiva (backoff, não presumir número exato) |
| Easy Apply / automação de candidaturas | Confirma-se que o LinkedIn mantém limites para conter automação — não relevante para a v1, pois candidaturas automatizadas estão fora de escopo |

## 5. Fora de escopo — confirmado que exige aprovação/parceria

| Produto | Situação |
|---|---|
| `w_organization_social` (postar em Company Page) | Requer aprovação via Community Management API |
| `r_member_social` (ler posts/feed do próprio membro) | LinkedIn pausou novas solicitações |
| Compliance API (`r_compliance`, `w_compliance`) | Restrita a empresas reguladas |
| Sales Navigator, Talent, Marketing (além do já listado) | Programas de parceiro próprios |

Confirma o que o planejamento original já previa: **nenhuma dessas entra na v1.**

## Decisão registrada — 22/09/2026

**Decisão:** o adaptador de publicação (Passo 9) será implementado contra a **Posts API** (`POST /rest/posts`), e não contra a UGC Post API legada citada no planejamento original. Confirmado pelo proprietário do projeto.

**Consequências para as próximas etapas:**
- O `LinkedInPublishingPort` e seus mapeadores (Passo 9) devem gerar o corpo plano da Posts API (`commentary`, `visibility`, `distribution`, `lifecycleState`), não o formato aninhado `specificContent."com.linkedin.ugc.ShareContent"`
- Toda chamada à Posts API precisa dos headers `Linkedin-Version: {YYYYMM}` e `X-Restli-Protocol-Version: 2.0.0`
- A versão (`Linkedin-Version`) deve ser uma propriedade de configuração (não hardcoded espalhada pelo código), revisada periodicamente — o LinkedIn publica uma nova versão por mês e mantém cada uma por no mínimo 1 ano
- A entidade `Publication.externalPostId` deve armazenar o valor do header de resposta `x-restli-id`
- A seção 2.2 (UGC Post API) deste documento fica só como referência histórica; não será implementada na v1

## Fontes consultadas

- Microsoft Learn — Posts API (`learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/posts-api`)
- Microsoft Learn — UGC Post API (`learn.microsoft.com/en-us/linkedin/compliance/integrations/shares/ugc-post-api`)
- Microsoft Learn — Share on LinkedIn (`learn.microsoft.com/en-us/linkedin/consumer/integrations/self-serve/share-on-linkedin`)
- Microsoft Learn — LMS API Documentation Versioning (`learn.microsoft.com/en-us/linkedin/marketing/versioning`)
- Microsoft Learn — Authorization Code Flow / Native Clients (`learn.microsoft.com/en-us/linkedin/shared/authentication/authorization-code-flow-native`)
- Fontes secundárias qualificadas sobre estado atual (2026) do acesso self-serve e limites: Blotato, Elfsight, ConnectSafely, Social-API.ai (usadas apenas para contexto de mercado, nunca como fonte de payload/endpoint — esses vieram das páginas oficiais Microsoft Learn)
