# Plano do Passo 8 — Ciclo OAuth com o LinkedIn

> **Plano e decisões da etapa concluída.** Documento histórico do planejamento e execução do Passo 8; o resumo vigente está em [`docs/status-atual.md`](../status-atual.md). Decisões atuais sobre a API do LinkedIn constam na [matriz de capacidades](../linkedin-capability-matrix.md).

**Status:** implementado e validado localmente; a configuração real do app no Developer Portal e o smoke test externo continuam pendentes.
**Escopo:** conectar e desconectar uma conta LinkedIn do usuário local autenticado, sem iniciar publicação ou qualquer outra funcionalidade de negócio.

## 1. Contexto confirmado no repositório

- O Passo 7 implementou contas locais, login por sessão HTTP e proteção CSRF. O início do OAuth deverá exigir a sessão do usuário local.
- `SecurityConfig` já exige autenticação para rotas privadas. As rotas OAuth ainda não existem; a política do callback deve ser definida explicitamente antes de implementá-lo.
- `AppProperties` e os perfis local/prod já expõem `client-id`, `client-secret`, `redirect-uri`, `token-encryption-key` e `base-url`. `.env.example` descreve os nomes esperados. Valores reais de `.env` não devem ser lidos, incluídos em commits ou solicitados ao usuário.
- V1 já contém `linkedin_authorization` e `oauth_transaction`; existem entidades JPA e repositórios para ambas. O schema prevê uma autorização por usuário, `state_hash` único, expiração e `consumed_at`.
- `OAuthTransactionRepository` atualmente apenas consulta por `stateHash`; consulta seguida de atualização simples não é suficiente para garantir consumo único concorrente. O caso de uso precisará de uma operação atômica/condicional.
- `LinkedInAuthorizationEntity` já prevê access/refresh token cifrados, identidade do membro, escopos, expiração e status. Não há ainda fluxo OAuth implementado, cifrador, cliente HTTP LinkedIn ou testes OAuth.
- A arquitetura vigente é hexagonal leve. Controllers e protocolo HTTP pertencem a `adapter.in.web`; orquestração pertence a `application`; a porta de registro já demonstra o padrão `application.ports.out`; repositórios JPA e clientes externos são adaptadores de saída.
- A matriz de capacidades registra OpenID Connect (`openid`, `profile`, `email` opcional), troca de código e endpoint UserInfo. Ela também recomenda não presumir refresh token. Revalidar esses fatos nas fontes oficiais no início do passo: uma matriz existente não substitui verificação atual.

## 2. Objetivo e fronteiras

Ao final do passo, um usuário autenticado deve poder iniciar uma autorização, concluir o retorno do LinkedIn, consultar um estado de conexão sanitizado e desconectar localmente. A autorização salva deve estar associada ao usuário que iniciou o fluxo, e nenhum segredo deve ser enviado ao navegador.

Incluído:

- Início do Authorization Code Flow no backend e redirecionamento ao endereço oficial de autorização.
- `state` aleatório de alta entropia, vinculado ao usuário e salvo somente como hash; expiração curta definida e testada; rejeição de divergência, expiração e reutilização; consumo concorrente único.
- Callback com tratamento separado para autorização recusada, parâmetros ausentes/malformados e sucesso.
- Troca server-to-server do código por token e busca apenas dos dados mínimos de identidade autorizados.
- Persistência da autorização cifrada e associada à conta local da transação, não a um ID recebido do cliente.
- Consulta da conexão sem revelar token, código, hash de `state` ou dados pessoais desnecessários.
- Desconexão local que remove ou invalida o segredo local conforme a decisão registrada; só implementar revogação remota se uma API oficial aplicável estiver confirmada.
- Erros externos convertidos em respostas controladas, sem propagar corpo bruto, códigos OAuth, tokens ou descrições potencialmente sensíveis.

Fora deste passo:

- Refresh automático de tokens, até que disponibilidade e contrato estejam confirmados para o app real.
- Revogação remota presumida.
- Publicações, Posts API, rascunhos, mídia, oportunidades, coleta de perfil além do identificador mínimo, permissões organizacionais e OAuth implícito/browser-only.
- Testes que chamem o LinkedIn real ou dependam de credenciais reais.

## 3. Verificações e decisões registradas

1. **Fontes e app:** foram revisadas as fontes oficiais de Authorization Code Flow, escopos e OpenID Connect/UserInfo. Endpoints usados: `https://www.linkedin.com/oauth/v2/authorization`, `https://www.linkedin.com/oauth/v2/accessToken` e `https://api.linkedin.com/v2/userinfo`. O registro do app, os produtos liberados, a redirect URI e os scopes habilitados no Developer Portal **não estão confirmados**; não houve chamada real ao LinkedIn.
2. **Contrato do cliente:** o callback retorna JSON sanitizado, sem redirecionamento ao frontend, pois ainda não há URL fixa aprovada. A recusa e os erros retornam códigos/mensagens genéricos. Nenhuma URL de retorno é aceita do request.
3. **Política de callback:** início e consulta exigem usuário local autenticado; callback é público e usa exclusivamente `state` aleatório, com hash persistido, expiração de dez minutos e consumo atômico de uso único para identificar o usuário.
4. **Reconexão:** a autorização existente permanece intacta durante a tentativa e só é substituída depois de troca do código, obtenção da identidade e cifragem bem-sucedidas.
5. **Desconexão:** remove a autorização e cancela transações pendentes localmente. Não declara nem executa revogação remota.
6. **Chave:** `TOKEN_ENCRYPTION_KEY` é Base64 com exatamente 32 bytes decodificados para AES-256-GCM. A chave é validada antes de iniciar o fluxo e novamente ao cifrar/decifrar; testes usam somente chave fictícia.
7. **Persistência:** entidades e DDL existentes suportam o fluxo; não foi necessária migration, mantendo V1/V2. Os testes aplicam ambas as migrations em SQLite vazio e validam o schema.
8. **Refresh:** o token de refresh é opcional e cifrado apenas se recebido. Não foi presumida disponibilidade no Developer Portal, e não há renovação automática implementada.

## 4. Organização prevista

Usar a arquitetura já adotada; criar apenas classes necessárias para comportamentos implementados:

- `adapter.in.web`: controller REST do ciclo OAuth e DTOs/respostas HTTP sanitizados.
- `application`: casos de uso de iniciar autorização, concluir callback, consultar conexão e desconectar; orquestração sem dependência de DTO externo, JPA ou controller.
- `application.ports.out`: contratos para cliente OAuth/UserInfo, persistência/consumo de transação, armazenamento de autorização e cifragem, conforme responsabilidades que os testes e o desenho final exigirem. Evitar interfaces especulativas sem consumidor.
- `adapter.out.linkedin`: construção da URL de autorização e cliente HTTP para troca do código e UserInfo; configuração segura, timeouts explícitos e respostas externas confinadas ao adaptador.
- `adapter.out.persistence`: implementação dos ports e operações transacionais sobre as entidades/repositórios OAuth já existentes.
- `adapter.out.security`: serviço de cifragem autenticada dos tokens e validação da chave, sem expor segredo fora da aplicação.
- `config` e `AppProperties`: propriedades necessárias, defaults não secretos apenas em `local` e validação fail-fast de requisitos criptográficos quando o recurso for usado.
- `domain.linkedin`: adicionar modelos de domínio somente se representarem conceitos/regras reais do fluxo; não criar classes vazias ou placeholders.

Não mover as classes de autenticação local do Passo 7 nem misturar OAuth LinkedIn com login local: são identidades e fluxos diferentes.

## 5. Segurança e comportamento técnico exigidos

- Gerar `state` com gerador criptograficamente seguro; enviar o valor original somente ao navegador para ida/volta e persistir apenas hash adequado. Comparação/consumo não pode aceitar vazio, truncamento ou reuso.
- Associar a transação à sessão/usuário que iniciou o fluxo e aplicar prazo de validade. O callback deve consumir a transação uma única vez antes de concluir efeitos que possam ser repetidos.
- Usar exclusivamente endpoints HTTPS oficiais e a URI de callback configurada; nunca construir redirect URI com `Host`/`Origin` da requisição.
- Enviar client secret e código somente ao endpoint de token server-to-server. Não os persistir nem incluí-los em URL, logs, exceções ou respostas.
- Cifrar tokens com cifra autenticada (AES-GCM ou alternativa aprovada), nonce único por operação e formato persistido versionável. Falha de autenticação ao decifrar deve falhar explicitamente; nunca retornar o texto cifrado como token.
- Armazenar somente campos autorizados e necessários. Persistir refresh token apenas se efetivamente retornado e confirmado; a ausência é normal e não pode impedir o sucesso do fluxo.
- Sanitizar logs HTTP; não registrar query string inteira do callback, headers Authorization, corpos de token/UserInfo ou exceções que os contenham.
- Redigir a resposta de conexão sem access/refresh token, chave, `state`, código OAuth ou payload bruto do provedor.

## 6. Plano de testes verificáveis

**Unidade**

- `state` tem entropia/formato esperado; somente o hash é persistido; estado incorreto, expirado ou já consumido falha.
- Duas tentativas concorrentes de consumir a mesma transação resultam em exatamente uma aceitação.
- Cifragem/decifragem de tokens funciona; nonce não é reutilizado; alteração do ciphertext/tag e chave inválida produzem erro explícito.
- URL de autorização inclui client ID, URI de callback exata, response type, scopes aprovados e `state` corretamente codificados, sem segredo.
- Conversão de expiração e resposta de token trata refresh token como opcional.

**Integração com provedor simulado**

- Mock HTTP para token e UserInfo: sucesso, OAuth error, 4xx, 5xx, timeout, resposta malformada/incompleta e UserInfo sem identificador obrigatório.
- Verificar destino, método, parâmetros, headers e timeouts; nenhum request vai à rede real nos testes.
- Sucesso persiste apenas valores cifrados e associa o `sub`/identificador devolvido à transação original.
- Falha após uso de state não permite replay nem cria autorização incompleta; comportamento de nova tentativa fica documentado.

**Banco e API**

- SQLite aplica constraints/foreign keys; migrações funcionam desde banco vazio e preservam dados V1/V2.
- MockMvc cobre início sem sessão (401), início autenticado (redirect), callback aprovado/recusado, parâmetros ausentes, state inválido/expirado/reutilizado, usuários isolados, consulta sem segredo e desconexão com sessão/CSRF corretos.
- Testar política definida de callback sem autenticação HTTP, sem perder a vinculação à conta que iniciou o fluxo.
- Confirmar por asserts que nenhuma resposta inclui token/código/state; verificar que logs de teste não capturam valores secretos.

Todos os testes são determinísticos, usam credenciais fictícias e cliente HTTP simulado. O smoke test manual com o Developer Portal é complementar, opcional e nunca substitui a suíte.

## 7. Implementação e validação executadas

1. Criados o caso de uso, portas, controller, adaptadores HTTP/JPA e cifrador AES-GCM.
2. Configurados timeouts de rede (5 s para conexão e 10 s para leitura), validação de credenciais/redirect/scopes e erros sanitizados.
3. Callback, consumo concorrente do state, persistência, isolamento de usuário, CSRF e desconexão foram exercitados por testes MockMvc/SQLite; chamadas do provedor foram simuladas.
4. Cifragem, cliente OAuth e regras da aplicação têm testes unitários com credenciais fictícias.
5. `mvn clean test` passou após as alterações.
6. Não houve alteração de schema nem migration nova.
7. O app no Developer Portal e o teste real via browser continuam pendentes de confirmação/configuração pelo proprietário.

## 8. Critérios de aceite

- Nenhuma chamada externa ocorre em unit tests ou na suíte CI.
- Um usuário não consegue associar ou consultar a autorização de outro.
- `state` é imprevisível, vinculado ao usuário, expira e só pode ser consumido uma vez mesmo sob concorrência.
- Tokens não são armazenados em claro nem devolvidos/logados.
- Recusa, erro de rede e respostas inesperadas têm comportamento explícito e não deixam autorização parcial.
- Reconexão, callback e desconexão seguem decisões documentadas; refresh/revogação não são inventados.
- Testes de unidade, integração/API e persistência passam; migration (se houver) é aditiva e validada.

## 9. Próxima ação necessária

Antes de tentar o fluxo real, confirmar no Developer Portal que o app tem os produtos e scopes necessários, cadastrar a redirect URI exata configurada localmente e garantir HTTPS/callback aceito pelo portal. Inserir client ID, client secret e uma chave Base64 válida apenas no ambiente local/secret manager; então executar o smoke test sem compartilhar esses valores. A indisponibilidade/ausência do app no portal não impede os testes automatizados, mas impede declarar a integração real ponta a ponta verificada.
