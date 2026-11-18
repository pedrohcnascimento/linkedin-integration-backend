# STEP-09 — Adaptador de publicação (entrega 1)

**Status:** em andamento. A fundação do adaptador foi implementada e validada; o caso de uso e a rota pública de publicação ficam para a próxima entrega, após a definição do Passo 10.

## Objetivo desta entrega

Encapsular a comunicação de publicação textual de membro com a LinkedIn Posts API, sem permitir que DTOs ou detalhes do provedor atravessem a fronteira da aplicação.

## Implementado

- `LinkedInPublicationCommand` e `PublicationResult` como modelos internos.
- Porta `LinkedInPublishingPort`.
- `LinkedInPublishingClientAdapter`, usando `POST /rest/posts`.
- Corpo oficial para post textual: `author`, `commentary`, `visibility`, `distribution`, `lifecycleState` e `isReshareDisabledByAuthor`.
- Headers oficiais `Authorization`, `X-Restli-Protocol-Version: 2.0.0` e `Linkedin-Version`.
- Captura do identificador `x-restli-id` na resposta `201 Created`.
- Versão mensal e URL base configuráveis por ambiente.
- Mapeamento de erros externos para códigos internos, sem devolver o payload bruto do LinkedIn.

## Validação

Comandos executados:

```text
mvn -q -DskipTests compile
mvn -q test
```

A suíte passou com 35 testes, sem falhas ou erros. Os testes do adaptador verificam método, URL, headers, corpo JSON, identificador de sucesso, rate limiting do provedor, resposta sem identificador e token ausente.

## Decisões

- A Posts API é usada em vez da UGC Post API legada.
- A versão inicial configurada é `202609`, a versão mais recente indicada pela documentação oficial consultada em 08/10/2026.
- O escopo desta entrega é publicação textual de perfil de membro. Imagens, vídeos, documentos, organizações, leitura de posts e agendamento não entram.
- Persistência local, aprovação, histórico e `Idempotency-Key` permanecem no Passo 10, para evitar uma publicação fora do fluxo aprovado.

## Pendências para concluir o Passo 9

- Criar o caso de uso de publicação, com obtenção segura da autorização ativa do usuário.
- Integrar o resultado ao modelo de publicação sem criar duplicação de responsabilidade com o Passo 10.
- Adicionar a rota pública somente quando o contrato de rascunho/aprovação/idempotência do Passo 10 estiver definido.
- Testar o fluxo de integração completo com um adaptador externo simulado por HTTP; nenhum token real deve ser usado na suíte.
