# Status atual do projeto

**Atualizado em:** 08/10/2026
**Etapa atual:** Passo 10 em andamento — rascunho aprovado e idempotência
**Referência de implementação:** [`docs/progress/STEP-10.md`](progress/STEP-10.md)

Este documento consolida o estado vigente. Os relatórios em `docs/progress/` preservam o registro original de cada etapa e podem conter decisões ou pendências que já foram superadas.

## Implementado

- Cadastro de usuários locais, autenticação por sessão, consulta do usuário autenticado, logout e proteção CSRF.
- Fluxo OAuth do LinkedIn: início, callback com `state` de uso único, consulta sanitizada da conexão e desconexão local.
- Smoke test real concluído com o aplicativo configurado no LinkedIn Developer Portal: autorização retornou `connected: true` e `status: ACTIVE`.
- Tokens de acesso cifrados em repouso; rate limiting para autenticação e rotas OAuth.
- Testes automatizados locais para os fluxos implementados. O provedor LinkedIn é simulado nos testes.
- Persistência separada por ambiente: SQLite no perfil `local` e Oracle no perfil `prod`, com migrations Flyway específicas para cada banco.
- Fundação do adaptador da Posts API: publicação textual de membro, versão configurável, headers oficiais e captura de `x-restli-id`.
- Fluxo de drafts: criação textual, listagem paginada, edição antes da aprovação, aprovação, publicação autenticada e replay idempotente por `Idempotency-Key`.

## Decisões vigentes

- **Licença:** MIT; o arquivo `LICENSE` contém o nome do titular.
- **Publicação futura:** usar LinkedIn Posts API (`POST /rest/posts`), não a UGC Post API legada. A versão mensal `Linkedin-Version` deverá ser configurável. Ver a decisão detalhada na [matriz de capacidades](linkedin-capability-matrix.md).
- **Refresh e revogação:** não presumir refresh token nem revogação remota; nenhum dos dois está implementado.
- **Escopo funcional atual:** drafts textuais podem ser criados, listados, editados antes da aprovação, aprovados e publicados uma vez com idempotência; histórico completo, mídia e oportunidades ainda não estão disponíveis.
- **Empacotamento:** JAR executável Spring Boot; Docker não está implementado.

## Pendências abertas

1. Validar o projeto com JDK 21 no ambiente-alvo, caso a execução de produção use uma instalação diferente.
2. Antes de exposição pública, validar rate limiting distribuído com Redis e o tratamento confiável do IP de origem no proxy.
3. Executar a validação Oracle prevista para o perfil de produção em um banco de teste descartável.
4. Concluir o Passo 9/10 com edição/listagem, histórico, retry seguro e concorrência de idempotência validados em SQLite e Oracle.
5. Implementar oportunidades locais (Passo 11) e, depois, API/OpenAPI, testes e hardening (Passos 12–14).

Os itens 1–3 são validações pendentes; os itens 4–5 são trabalho planejado. O fluxo textual de drafts/publicação já é funcional, mas ainda não representa a superfície completa prevista no roteiro.

## Precedência documental

Em caso de divergência, use a fonte adequada ao assunto, nesta ordem:

1. **Estado vigente, decisões atuais e pendências:** este documento; o README resume esse estado para usuários.
2. **Fatos, endpoints e decisões sobre capacidades do LinkedIn:** [`docs/linkedin-capability-matrix.md`](linkedin-capability-matrix.md). Ela prevalece sobre descrições antigas no planejamento e nos relatórios.
3. **Roteiro e requisitos planejados:** [`docs/planejamento-tecnico.md`](planejamento-tecnico.md). O planejamento define o que fazer, não atesta implementação.
4. **Histórico de execução e decisões no momento de cada etapa:** [`docs/progress/`](progress/). Esses relatórios são registros históricos, não o status vigente.

## Decisões anteriores resolvidas

- O Passo 1 registrou como pendente a confirmação do titular da licença MIT; essa pendência foi resolvida, conforme `LICENSE`.
- O Passo 2 registrou como pendente a escolha entre Posts API e UGC Post API; a decisão vigente é Posts API, confirmada em `docs/progress/STEP-02b.md` e detalhada na matriz.
