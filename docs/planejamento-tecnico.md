# Planejamento técnico — Backend para integração autorizada com o LinkedIn

**Versão:** 1.0 — planejamento inicial
**Status:** documento de arquitetura e roteiro; o estado implementado e os próximos critérios estão registrados em `docs/progress/`.
**Tecnologias obrigatórias:** Java 21, Spring Boot, Spring Web, Spring Security quando necessário, Maven, SQLite, JPA/Hibernate, JDBC subjacente, APIs RESTful, OAuth 2.0 e Docker quando aplicável.

> Este arquivo é a fonte de verdade **arquitetural** do projeto. Qualquer pessoa ou agente de IA que for alterar o repositório deve lê-lo por completo antes de programar.
>
> **Atualização de 22/09/2026 (Passo 2 do roteiro):** o endpoint de publicação citado na seção 3.1 (`POST /v2/ugcPosts`) é uma API legada desde 30/06/2023. Após validação com a documentação oficial atual, o projeto adota a **Posts API** (`POST /rest/posts`) no lugar. Ver decisão registrada e detalhes completos em [`docs/linkedin-capability-matrix.md`](linkedin-capability-matrix.md) — esse arquivo é a fonte de verdade **factual/atualizável** sobre a API do LinkedIn e prevalece sobre este documento sempre que houver conflito.

## 1. Resumo executivo

A primeira versão deve ser um backend próprio, modular e orientado a uma integração autorizada com o LinkedIn. O sistema **não** deve automatizar a navegação no site, coletar dados por scraping, usar cookies de sessão nem imitar cliques do usuário. Essas práticas são proibidas ou arriscadas segundo o Contrato do Usuário e a documentação de software proibido do LinkedIn.

A integração inicial deve priorizar o produto **Share on LinkedIn**. A permissão aberta `w_member_social` permite criar publicações em nome do membro autenticado. Conforme a decisão registrada na matriz de capacidades, o adaptador futuro usará a Posts API (`POST https://api.linkedin.com/rest/posts`), não o endpoint UGC legado. A aplicação deve tratar a publicação como uma operação explicitamente autorizada pelo usuário, com registro local e idempotência.

Busca, salvamento e candidatura a vagas **não** devem ser automatizadas dentro do LinkedIn na primeira versão — não há permissão aberta documentada para isso, e o LinkedIn limita o Easy Apply para conter bots. O sistema pode manter uma lista própria de oportunidades obtidas de fontes autorizadas, com revisão e abertura de links manual pelo usuário.

A arquitetura recomendada é **hexagonal leve** (ports and adapters), separando domínio das regras de negócio dos detalhes do LinkedIn, do Spring MVC e do SQLite — facilitando testes, mudanças na API externa e futura migração para PostgreSQL.

## 2. Escopo e limites da primeira versão

### 2.1 Escopo funcional recomendado

1. Cadastro lógico do usuário local da aplicação
2. Início e conclusão do fluxo OAuth 2.0 com o LinkedIn
3. Armazenamento seguro da autorização do LinkedIn
4. Obtenção dos dados mínimos de identidade necessários para associar a autorização ao usuário local
5. Criação de rascunhos de publicação
6. Revisão e aprovação explícita de rascunhos
7. Publicação de texto, URL e, em etapa posterior, mídia (conforme recursos documentados do Share on LinkedIn)
8. Histórico das publicações iniciadas pela aplicação
9. Tratamento de erros, expiração de tokens, limites e falhas transitórias
10. API REST própria, documentada com OpenAPI
11. Testes automatizados e documentação adequada para portfólio

### 2.2 Fora do escopo inicial

Não devem ser implementados sem documentação e aprovação específicas do LinkedIn:

- Scraping de perfis, vagas, publicações ou resultados de pesquisa
- Login automatizado por navegador, uso de cookies ou automação de DOM
- Envio automático de convites ou mensagens
- Automação de curtidas, comentários ou compartilhamentos fora dos endpoints/permissões confirmados
- Busca de vagas diretamente pela interface do LinkedIn
- Salvamento automático de vagas dentro da conta do LinkedIn
- Candidatura automática, inclusive Easy Apply
- Automação de Sales Navigator
- Coleta ampla de analytics, conexões ou dados de terceiros
- Publicação sem aprovação ou regra de autorização local claramente definida

## 3. Validação da API oficial do LinkedIn

### 3.1 Capacidade confirmada para a primeira versão

| Funcionalidade | Produto/endpoint | Permissão | Aprovação especial | Limitação arquitetural |
|---|---|---|---|---|
| Autenticar o membro | OAuth 2.0 do LinkedIn | Escopos solicitados pelo app | App precisa estar registrado e autorizado pelo membro | Backend deve validar `state`, redirecionamento e troca segura do código |
| Criar publicação | Share on LinkedIn; `POST /rest/posts` | `w_member_social` | Classificada como permissão aberta; requer configurar o produto no Developer Portal | Publicação imediata quando `lifecycleState=PUBLISHED`; agendamento é responsabilidade da aplicação. Usar `Linkedin-Version` conforme a decisão registrada na matriz |
| Publicar texto | Share on LinkedIn | `w_member_social` | Não indicada aprovação de parceiro | Corpo deve usar o formato oficial e visibilidade aceita pela API |
| Compartilhar URL | Share on LinkedIn | `w_member_social` | Não indicada aprovação de parceiro | URL e metadados no formato documentado |
| Publicar imagem/vídeo | Share on LinkedIn | `w_member_social` | Confirmar requisitos de upload no momento da implementação | Registrar e enviar mídia antes de criar a publicação |

As permissões abertas disponíveis a todos os desenvolvedores incluem **Share on LinkedIn** com `w_member_social`. Isso não deve ser interpretado como autorização para automatizar ações por navegador ou para qualquer operação não documentada. Cada ação só deve ser implementada quando endpoint, corpo, permissão e limites estiverem confirmados na documentação atual.

### 3.2 Capacidades que exigem confirmação ou aprovação

Marketing, Sales Navigator, Talent, Compliance e outros produtos têm permissões e programas próprios, muitos exigindo aprovação explícita ou participação em programas de parceiros. O projeto deve manter uma **matriz de capacidades versionada** (`docs/linkedin-capability-matrix.md`) com colunas: produto, endpoint, escopo, aprovação, finalidade, retenção autorizada, limite e data da última verificação.

Não implementar uma funcionalidade apenas porque ela existe no site do LinkedIn — o fato de um usuário conseguir pesquisar/salvar/candidatar-se manualmente não demonstra a existência de uma API pública equivalente.

### 3.3 Vagas de emprego

Vagas são tratadas como fonte externa ou entrada manual do usuário. O backend pode armazenar uma oportunidade inserida pelo usuário ou vinda de fonte autorizada (desde que os termos da fonte permitam). O sistema pode calcular compatibilidade com currículo, classificar oportunidades e guardar status local — **nunca** abrindo sessão automatizada do LinkedIn.

Fluxo permitido: coletar de fonte autorizada → filtrar localmente → apresentar ao usuário → abrir link por ação do usuário → salvamento/candidatura sob controle manual.

## 4. Arquitetura proposta

### 4.1 Princípios

Separação de responsabilidades, baixo acoplamento, alta coesão, SOLID, Clean Code e DDD onde reduzir complexidade. O domínio não deve conhecer classes de resposta do LinkedIn, entidades JPA ou detalhes de HTTP.

Quatro áreas:
1. **Domínio:** regras, entidades de negócio, objetos de valor e portas
2. **Aplicação:** casos de uso, transações e orquestração
3. **Adaptadores de entrada:** controllers REST, autenticação e validação de requests
4. **Adaptadores de saída:** JPA, cliente HTTP do LinkedIn, armazenamento de segredos e observabilidade

### 4.2 Estrutura de pacotes

```
com.example.linkedinagent
├── domain
│   ├── user
│   ├── linkedin
│   ├── publishing
│   ├── opportunity
│   └── common
├── application
│   ├── auth
│   ├── publishing
│   ├── opportunity
│   └── ports
│       ├── in
│       └── out
├── adapter
│   ├── in
│   │   └── web
│   └── out
│       ├── linkedin
│       ├── persistence
│       └── security
├── config
├── exception
└── shared
```

Alternativa mais simples para o primeiro commit: organizar por feature, mantendo `domain`, `application` e `adapter` dentro de cada feature. Regra essencial: **os DTOs externos do LinkedIn ficam dentro do adaptador `linkedin` e nunca atravessam a fronteira do caso de uso.**

### 4.3 Portas e adaptadores

**Portas de entrada:**
- `StartLinkedInAuthorizationUseCase`
- `CompleteLinkedInAuthorizationUseCase`
- `CreateDraftUseCase`
- `ApproveDraftUseCase`
- `PublishApprovedDraftUseCase`
- `ListPublicationHistoryUseCase`
- `RegisterOpportunityUseCase`

**Portas de saída:**
- `LinkedInAuthorizationPort`
- `LinkedInProfilePort`
- `LinkedInPublishingPort`
- `AccessTokenRepository`
- `UserRepository`
- `DraftRepository`
- `PublicationRepository`
- `ClockPort` (para testes determinísticos)

A implementação HTTP do LinkedIn deve converter respostas externas em modelos internos. O domínio trabalha com `LinkedInPublicationCommand` e `PublicationResult` — nunca com `com.linkedin.ugc.ShareContent` ou mapas JSON crus.

## 5. Fluxo OAuth 2.0

### 5.1 Fluxo recomendado

1. Usuário autenticado na aplicação solicita conexão com o LinkedIn
2. Backend gera `state` criptograficamente aleatório, curto e de uso único
3. Backend redireciona para autorização do LinkedIn com `client_id`, `redirect_uri`, `response_type=code`, `state` e escopos necessários
4. Membro autoriza o aplicativo
5. LinkedIn redireciona ao callback do backend com `code` e `state`
6. Backend compara `state` com valor armazenado; rejeita reutilização ou divergência
7. Backend troca o código por access token via TLS, credenciais em segredo
8. Backend consulta apenas os dados mínimos para identificar o membro e associa a autorização ao usuário local
9. Backend armazena o token de forma protegida e remove `code`/`state` temporários
10. Aplicação informa ao usuário que a conexão foi concluída

O frontend **nunca** recebe `client_secret`, refresh token ou access token de longa duração. O callback é processado exclusivamente pelo backend.

### 5.2 Tokens e sessões

Armazenar o mínimo necessário: referência do usuário local, identificador do membro (quando permitido), access token cifrado, tipo, escopos concedidos, data de emissão, data de expiração, data da última utilização, status de revogação.

Refresh tokens não devem ser presumidos — verificar documentação e resposta efetiva. Se existir, tratar como segredo de alto valor. Se não existir, marcar autorização como expirada e exigir nova autorização.

**Nunca armazenar:** senha do LinkedIn, cookies, dados completos de sessão do navegador, código OAuth após a troca, tokens em logs, respostas externas completas sem finalidade definida, dados de terceiros além do necessário.

### 5.3 Proteções de segurança

- `state` de uso único contra CSRF no OAuth
- `redirect_uri` exata, sem curingas
- HTTPS em produção
- Segredo do cliente em variável de ambiente/secret manager
- Cifragem em repouso dos tokens, com chave fora do banco
- Rotação planejada de chaves
- Logs com mascaramento de tokens e identificadores
- Expiração de sessão local
- Autorização por usuário (e futuramente por papéis)
- CORS restrito às origens conhecidas
- CSRF habilitado para sessões baseadas em cookie, ou justificativa documentada se a API usar apenas bearer tokens
- Rate limiting nos endpoints de OAuth e publicação
- Validação de tamanho, tipo e conteúdo dos campos
- Tratamento uniforme de falhas sem revelar detalhes internos

## 6. Modelo de dados SQLite com JPA/Hibernate

### 6.1 Entidades principais

| Entidade | Finalidade | Atributos essenciais |
|---|---|---|
| `AppUser` | Usuário da aplicação | `id`, `email`, `displayName`, `status`, `createdAt`, `updatedAt` |
| `LinkedInAuthorization` | Autorização concedida ao usuário | `id`, `appUserId`, `memberSubject`, `encryptedAccessToken`, `encryptedRefreshToken` (opcional), `scopes`, `expiresAt`, `status`, `createdAt`, `updatedAt` |
| `ContentDraft` | Rascunho editável | `id`, `appUserId`, `text`, `mediaCategory`, `originalUrl`, `title`, `status`, `approvedAt`, `createdAt`, `updatedAt` |
| `Publication` | Histórico de tentativa/publicação | `id`, `appUserId`, `draftId`, `externalPostId`, `requestFingerprint`, `status`, `failureCode`, `publishedAt`, `createdAt` |
| `Opportunity` | Vaga mantida localmente | `id`, `appUserId`, `source`, `externalUrl`, `title`, `companyName`, `location`, `descriptionSnapshot` (opcional), `matchScore` (opcional), `status`, `foundAt`, `expiresAt` (opcional) |
| `OAuthTransaction` | Estado temporário do OAuth | `id`, `appUserId`, `stateHash`, `scopes`, `expiresAt`, `consumedAt`, `createdAt` |
| `AuditEvent` | Auditoria de ações relevantes | `id`, `appUserId`, `eventType`, `resourceType`, `resourceId`, `outcome`, `occurredAt`, `metadataSanitized` |

`LinkedInAuthorization` deve ter restrição única para impedir duas autorizações ativas do mesmo usuário e, quando aplicável, restrição para o identificador externo do membro. Tokens sempre cifrados antes de persistir; o segredo de cifragem não pode ficar na mesma tabela nem no repositório Git.

`ContentDraft` guarda somente o conteúdo que o usuário deseja gerenciar, com minimização, retenção e base legal adequadas para qualquer descrição de vaga armazenada.

### 6.2 Relacionamentos JPA

- `AppUser` → `@OneToMany(mappedBy = "appUser")` com autorizações, rascunhos, publicações e oportunidades
- `LinkedInAuthorization`, `ContentDraft`, `OAuthTransaction`, `AuditEvent` → `@ManyToOne(fetch = LAZY)` com `AppUser`
- `Publication` → `@ManyToOne(fetch = LAZY)` com `AppUser` e, opcionalmente, `ContentDraft`

Não usar `EAGER` por padrão. Preferir consultas específicas, projeções e `JOIN FETCH` controlado. Evitar `@ManyToMany` na primeira versão.

### 6.3 SQLite e migração

- Usar tipos portáveis e tamanhos de coluna razoáveis
- Evitar SQL específico de SQLite na camada de domínio
- Evitar `rowid`, `WITHOUT ROWID`, triggers proprietárias, funções específicas
- IDs gerados pela aplicação (ou estratégia compatível)
- Transações curtas
- Habilitar foreign keys na conexão
- Considerar concorrência e bloqueio de escrita
- Evitar objetos JSON como única representação de dados consultáveis
- `TEXT` para timestamps ISO-8601
- Índices explícitos para chaves de busca
- Migrations versionadas (Flyway), validadas contra SQLite

`ddl-auto=update` **nunca** em produção. Em desenvolvimento inicial, `validate` com migrations versionadas é mais previsível.

## 7. API REST própria

Versionada desde o início com prefixo `/api/v1`.

### 7.1 Endpoints iniciais

| Método e rota | Finalidade | Autenticação | Resposta principal |
|---|---|---|---|
| `GET /api/v1/linkedin/oauth/start` | Iniciar OAuth | Usuário local | `302` para o LinkedIn |
| `GET /api/v1/linkedin/oauth/callback` | Processar callback | `state` válido | `302` ou resposta de conclusão |
| `GET /api/v1/linkedin/connection` | Ver estado da conexão | Usuário local | `200` com estado sanitizado |
| `DELETE /api/v1/linkedin/connection` | Revogar conexão local | Usuário local | `204` |
| `POST /api/v1/drafts` | Criar rascunho | Usuário local | `201` |
| `GET /api/v1/drafts` | Listar rascunhos paginados | Usuário local | `200` |
| `GET /api/v1/drafts/{id}` | Consultar rascunho | Usuário local | `200` |
| `PATCH /api/v1/drafts/{id}` | Editar rascunho | Usuário local | `200` |
| `POST /api/v1/drafts/{id}/approve` | Aprovar publicação | Usuário local | `200` |
| `POST /api/v1/drafts/{id}/publish` | Publicar rascunho aprovado | Usuário local | `202` ou `201` |
| `GET /api/v1/publications` | Histórico de publicações | Usuário local | `200` |
| `POST /api/v1/opportunities` | Registrar oportunidade local | Usuário local | `201` |
| `GET /api/v1/opportunities` | Filtrar oportunidades próprias | Usuário local | `200` |
| `PATCH /api/v1/opportunities/{id}` | Atualizar status local | Usuário local | `200` |
| `GET /actuator/health` | Health check | Conforme exposição configurada | `200` ou `503` |

O endpoint de publicação deve verificar propriedade do rascunho, status `APPROVED`, existência de autorização ativa e ausência de publicação anterior com a mesma impressão digital de requisição. Exigir cabeçalho de idempotência (`Idempotency-Key`) para evitar duplicação em retries.

### 7.2 DTOs e erros

DTOs de entrada usam Bean Validation. O domínio **não** recebe `@RequestBody` diretamente. Erros seguem `application/problem+json` com `type`, `title`, `status`, `detail`, `instance`, `traceId` e lista de erros de campo quando aplicável.

Códigos: `400` payload inválido · `401` ausência de autenticação · `403` falta de autorização · `404` recurso inexistente do usuário atual · `409` conflito de estado/idempotência · `422` rascunho não publicável · `429` limite local/externo · `502`/`504` falha transitória do LinkedIn · `500` apenas falhas inesperadas, sem vazar stack trace.

## 8. Regras de negócio

Estados mínimos de `ContentDraft`: `DRAFT`, `APPROVED`, `PUBLISHING`, `PUBLISHED`, `FAILED`. Transições inválidas rejeitadas pelo domínio.

Publicação é operação explícita. Agendamento local é permitido, mas o worker só envia conteúdo aprovado, dentro do horário definido e com autorização válida — a aplicação não deve afirmar que o LinkedIn tem agendamento nativo se isso não estiver documentado.

Idempotência combina usuário, rascunho, versão do conteúdo e chave fornecida pelo cliente. Resposta externa perdida após publicação não deve gerar duplicata sem estratégia de reconciliação segura.

## 9. Testes

**Unitários (JUnit + Mockito):** transições de estado, validação de autorização, decisão de publicação, cálculo de idempotência, token expirado, conversores internos↔DTO, mapeadores de erro, retenção de oportunidades.

**Integração:** Spring Boot Test + MockMvc + SQLite real (arquivo temporário ou memória). Cliente HTTP do LinkedIn testado com WireMock/MockWebServer cobrindo sucesso, 4xx, 5xx, timeout, JSON inesperado, token expirado, erro de upload. Callback OAuth testado com `state` correto/incorreto/expirado/reutilizado. Confirmar ausência de tokens em logs e isolamento entre usuários.

**Controller/segurança:** MockMvc verificando autenticação, autorização, validação, códigos HTTP, headers de correlação, resposta de erro, CORS. Controllers finos; lógica testada nos casos de uso.

## 10. Observabilidade e qualidade

Logs estruturados com `traceId`/`requestId`. Logs de publicação registram usuário local, rascunho, operação, resultado e latência — **nunca** tokens, códigos OAuth, conteúdo sensível desnecessário ou respostas completas do LinkedIn.

Actuator expõe apenas health checks necessários. Métricas: latência por operação, sucesso/falha de publicação, respostas 4xx/5xx, tokens expirados, tamanho da fila local. Endpoints de observabilidade não públicos sem proteção.

Documentação via springdoc-openapi. README com diagrama, fluxo OAuth, configuração, limitações conhecidas, modelo de dados, exemplos curl sanitizados, execução de testes e revogação de conexão.

Docker para padronizar ambiente, volume persistente para SQLite, secrets por ambiente. Imagem sem `.env`, tokens ou banco com dados reais.

## 11. LGPD e privacidade

Este documento não substitui orientação jurídica. Tecnicamente: definir finalidade, necessidade, retenção e controle de acesso antes de armazenar dados do LinkedIn. Coleta mínima, limitada ao funcionamento declarado. Separar dados do usuário local, tokens, dados de publicação e oportunidades. Nunca coletar dados de terceiros por scraping.

Usuário deve conseguir desconectar o LinkedIn — apagando/inutilizando tokens, revogando autorização quando suportado, registrando o evento sem guardar o segredo. Rotina de exclusão/anonimização conforme finalidade e política de retenção.

Banco com controle de acesso por usuário, backups cifrados. Documentar quais dados vêm do LinkedIn, quais são criados pelo usuário e quais são derivados. Acesso interno aos tokens restrito ao adaptador de integração.

## 12. Persistência PostgreSQL em produção

O perfil `prod` usa o driver JDBC PostgreSQL gerenciado pelo Spring Boot, configura o dialeto PostgreSQL e mantém `ddl-auto=validate`. O perfil local continua usando SQLite. As migrations Flyway V1/V2 usam tipos portáveis (`TEXT`, `INTEGER`) e são aplicadas na inicialização; o Hibernate valida o mapeamento das entidades contra o schema já migrado.

Antes de um deploy:

1. Fornecer `DATABASE_URL` no formato `jdbc:postgresql://<host>:<porta>/<banco>` e `DATABASE_USERNAME`/`DATABASE_PASSWORD` por secret manager ou configuração do serviço; configurar TLS conforme o provedor.
2. Usar uma conta com permissões de migration na inicialização e manter o histórico Flyway acessível.
3. Em um PostgreSQL de teste vazio e descartável, executar `mvn -Dtest=PostgreSqlProfileIntegrationTest test`. O teste inicia com perfil `prod`, aplica V1/V2, verifica conexão e validação Hibernate e executa operações de repositório para usuário, autorização LinkedIn, UUID, timestamps e consumo de transação.
4. Executar a suíte geral `mvn clean test`; ela permanece isolada em SQLite e não acessa serviços externos.
5. Só então promover a mesma configuração de schema para produção. Não execute o teste de integração em banco produtivo: ele grava dados.

Sem `POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME` e `POSTGRES_TEST_PASSWORD`, o teste PostgreSQL é ignorado. O teste automatizado verifica operações JPA atuais (usuário e transação OAuth); tabelas de publicação/oportunidade não têm casos de uso implementados nesta etapa e não há queries de negócio dessas funcionalidades a validar ainda.

Entidades, serviços, controllers, portas e regras de negócio não deveriam depender do banco concreto. Qualquer evolução nas migrations deve continuar compatível com SQLite e PostgreSQL ou declarar scripts separados por banco, acompanhados por testes nos dois perfis.

## 13. Configuração por ambiente

Perfis `local`, `test` e `prod`. Valores sensíveis via variáveis de ambiente:

```
LINKEDIN_CLIENT_ID
LINKEDIN_CLIENT_SECRET
LINKEDIN_REDIRECT_URI
TOKEN_ENCRYPTION_KEY
APP_BASE_URL
DATABASE_URL
DATABASE_USERNAME
DATABASE_PASSWORD
CORS_ALLOWED_ORIGINS
```

`application.yml` pode conter defaults não sensíveis para `local`, mas nunca o segredo real. Perfil `test` usa credenciais fictícias e servidor HTTP simulado.

## 14. Roadmap recomendado (visão macro)

| Etapa | Objetivo | Critério de conclusão |
|---|---|---|
| 1 — Validação documental | Confirmar produtos, endpoints, permissões, limites e políticas atuais | Nenhuma funcionalidade planejada depende de endpoint inventado |
| 2 — Aplicação LinkedIn | Criar app no Developer Portal, habilitar só Share on LinkedIn | OAuth manual funciona local sem expor segredo |
| 3 — Arquitetura e bootstrap | Projeto Spring Boot com Maven, Java 21, profiles, estrutura de pacotes | Build limpo e README inicial |
| 4 — Persistência SQLite | Driver JDBC, JPA/Hibernate, migrations, entidades iniciais | Testes de persistência passam em SQLite real |
| 5 — Identidade e OAuth | Usuário local, state único, callback, token cifrado, desconexão | Testes de sucesso/expiração/replay/erro passam |
| 6 — Adaptador LinkedIn | Cliente HTTP isolado e mapeadores para Share API | Testes simulados cobrem sucesso e falha |
| 7 — Rascunhos e publicação | Estados, aprovação, idempotência, histórico, retry seguro | Sem publicação duplicada em retry conhecido |
| 8 — Oportunidades locais | Registrar/classificar vagas de fontes autorizadas ou manuais | Sistema não precisa da sessão do LinkedIn |
| 9 — API REST e documentação | Controllers, DTOs, validação, erros, OpenAPI, versionamento | Exemplos do README executam localmente |
| 10 — Segurança/privacidade/observabilidade | CORS, CSRF, rate limit, logs, auditoria, exclusão, retenção | Sem tokens em logs nem acesso cruzado |
| 11 — Docker e qualidade de portfólio | Execução padronizada, diagrama, documentação, testes, CI futuro | Clone novo inicia seguindo o README |
| 12 — Preparação para PostgreSQL | Validar independência do domínio em relação ao banco | Migrations e testes passam nos dois bancos |

A ordem reduz retrabalho: valida a plataforma real primeiro, estabiliza a fronteira externa antes das regras de negócio, mantém a persistência substituível.

## 15. Roteiro operacional cronológico (passo a passo executável)

> Cada passo deve ser concluído antes do próximo, salvo indicação de atividade paralela. Qualquer agente de IA responsável por uma etapa deve ler este documento inteiro antes de alterar o repositório, respeitar as restrições da seção 2 e não inventar capacidades da API do LinkedIn.

### Regras para qualquer agente de IA

Antes de modificar o projeto:
1. Confirmar o objetivo da etapa atual
2. Inspecionar estrutura existente, branch ativo, build e testes
3. Ler README, configurações e migrations já existentes
4. Procurar implementações duplicadas antes de criar novos arquivos
5. Propor alterações pequenas e coerentes com a arquitetura
6. Nunca adicionar tokens, senhas, cookies, `.env` reais ou dados pessoais ao Git
7. Não implementar endpoint, scope ou produto do LinkedIn não confirmado na documentação oficial
8. Escrever ou atualizar testes junto com a alteração
9. Executar build e testes relevantes
10. Registrar no relatório final: arquivos alterados, comandos executados, resultado dos testes, riscos e próximo passo

O agente **não deve avançar automaticamente** quando um teste falhar, uma migration for destrutiva, uma permissão do LinkedIn estiver indefinida ou uma decisão puder alterar o modelo de segurança. Nesses casos, registrar a dúvida e solicitar decisão antes de continuar.

### Passo 1 — Criar o repositório e o contrato de trabalho
**Objetivo:** estabelecer um repositório reproduzível e regras para futuras contribuições humanas e automatizadas, sem implementar regras de domínio.
**Escopo realizado:** README e visão do produto; licença MIT; `.gitignore`; `CONTRIBUTING.md`; `SECURITY.md`; planejamento técnico; convenções Java 21/Maven, branch e commits; pasta de relatórios de progresso.
**Adições benéficas:** as instruções para agentes e o checklist de passagem entre etapas tornam explícitos os limites de escopo, revisão, testes, proteção de segredos e necessidade de parar diante de decisões de segurança ou migrations ambíguas.
**Verificação:** conferir clone limpo, arquivos de configuração e banco local ignorados pelo Git; conferir que `.env.example`, não `.env`, é versionado; README explica a execução disponível no estágio.
**Estado/pendências:** licença atualmente contém o nome do titular, resolvendo o placeholder registrado historicamente em `STEP-01.md`. Maven Wrapper não foi adicionado; é uma melhoria opcional, não um requisito para considerar o contrato documental concluído. Não adicionar código de domínio retroativamente a esta etapa.

### Passo 2 — Validar a API e congelar o escopo da v1
**Objetivo:** transformar a pesquisa da API do LinkedIn em decisões implementáveis e impedir que funcionalidades sejam inferidas apenas pela interface do site.
**Escopo realizado:** matriz `docs/linkedin-capability-matrix.md`, fontes e requisitos para OpenID Connect/UserInfo e Share on LinkedIn, limites de escopos e decisões registradas em `STEP-02.md` e `STEP-02b.md`.
**Decisão vinculante:** usar Posts API (`POST /rest/posts`) no futuro, em vez da UGC Post API legada; manter `Linkedin-Version` configurável e não presumir refresh token. A matriz é a referência factual para endpoints, versões e capacidades.
**Adições benéficas:** identidade mínima por OIDC/UserInfo, exclusão explícita de scraping e de operações que exijam outras aprovações, e tratamento defensivo de `429` sem inventar limites numéricos.
**Verificação:** para cada chamada efetivamente implementada, registrar fonte oficial, verbo/endpoint, escopo, payload, resposta, falhas e retenção. Manter data de consulta e distinção entre fontes primárias e secundárias.
**Pendência:** a matriz foi produzida em 2026 e deve ser revalidada em fontes oficiais antes dos Passos 8 e 9; disponibilidade listada publicamente não prova que o app específico foi aprovado ou recebeu determinado produto/escopo.

### Passo 3 — Registrar o aplicativo no LinkedIn sem expor segredos
**Objetivo:** preparar a aplicação de desenvolvimento e seu callback para OAuth sem colocar credenciais no repositório, nos logs ou em mensagens.
**Ações previstas:** registrar o app no Developer Portal; associar a LinkedIn Page quando exigido; habilitar apenas produtos necessários (Sign In with LinkedIn using OpenID Connect e Share on LinkedIn); configurar a redirect URI exata; guardar client ID/secret somente no ambiente local ou secret manager.
**Integração com o código existente:** `.env.example` documenta `LINKEDIN_CLIENT_ID`, `LINKEDIN_CLIENT_SECRET`, `LINKEDIN_REDIRECT_URI` e `TOKEN_ENCRYPTION_KEY`; `application-local.yml` fornece defaults de desenvolvimento e a URI local prevista.
**Segurança/verificação:** redirect URI no portal deve ser byte a byte igual à configuração usada no fluxo; `.env` e bancos locais devem continuar fora do Git; não colar credenciais em issues, logs ou documentação versionada. Smoke test real fica restrito a ambiente próprio autorizado e não substitui os testes automatizados.
**Estado:** **não comprovado pelos artefatos versionados**. Não existe `STEP-03.md` nem evidência documental de que o app e os produtos foram criados/configurados; variáveis no YAML ou `.env.example` não provam cadastro ou aprovação. Confirmar manualmente no portal antes do Passo 8, sem compartilhar valores secretos. Se essa ação ainda não ocorreu, o Passo 3 continua pendente.

### Passo 4 — Gerar o esqueleto Spring Boot
**Objetivo:** fornecer uma aplicação pequena, compilável e iniciável, com health check, configuração Maven e teste de contexto, sem lógica de negócio em controller.
**Escopo realizado:** classe principal mínima, Spring Boot 4.1.0, Java release 21, Maven, starters modulares Boot 4, Actuator, springdoc e perfil de teste; `ddl-auto: validate` em vez de atualização automática.
**Adições benéficas:** teste de contexto sem credenciais LinkedIn e migrations controladas por Flyway desde cedo; a correção registrada em `STEP-04b.md` colocou a URL SQLite em formato YAML válido.
**Verificação retrospectiva:** em 29/09/2026 `mvn clean test` concluiu com 21 testes, sem falhas/erros, e `mvn -DskipTests compile` compilou 39 fontes. Assim, a pendência histórica de build em `STEP-04.md` foi posteriormente resolvida. Essa execução usou JDK 25 com `--release 21`; validar também com JDK 21 para corresponder ao ambiente-alvo.
**Pendência de cobertura:** a aplicação sobe e Actuator expõe `health`, mas a suíte atual não tem teste HTTP explícito do endpoint de health nem execução de uma imagem empacotada. Adicionar/verificar isso antes de declarar esses contratos exercitados; não é bloqueio para o OAuth em si.
**Ferramentas:** wrapper Maven não está incluído; seu uso é opcional, mas adicioná-lo reduz divergências de versão Maven entre ambientes.

### Passo 5 — Configurar ambientes e segredos
**Objetivo:** separar configurações `local`, `test` e `prod` e tipar as propriedades usadas pela aplicação, sem versionar segredos.
**Escopo realizado:** `application.yml` base; perfis local/prod/test; `AppProperties`; `VerifyEnvRunner`; `WebConfig`; carregamento de `.env` com spring-dotenv; `.env.example`; CORS restrito por configuração.
**Adições benéficas posteriores:** cookies de sessão `HttpOnly` e `SameSite=Lax`, `Secure` em produção, e `PRAGMA foreign_keys=ON` em conexões SQLite local/test foram introduzidos com o Passo 7 e estão registrados como requisitos de ambiente relevantes.
**Verificação:** teste de binding/contexto com perfil test; revisar que todos os defaults são não secretos; testar CORS com origens permitidas e rejeitadas; validar cookies e ausência de fallback para valores sensíveis no perfil prod.
**Pendências/limites atuais:** `application-prod.yml` referencia credenciais PostgreSQL, mas o projeto atual só declara driver SQLite; o perfil prod não deve ser considerado pronto para implantação até o Passo 12 e não foi validado por estes testes. O teste usa strings fictícias — incluindo uma chave de cifragem de exemplo que ainda não tem contrato binário validado. Antes do OAuth, definir e validar o formato/tamanho real da chave sem inserir um valor real no Git. Conferir também parsing/trim de múltiplas origens CORS e não tratar a configuração local permissiva como configuração de produção.

### Passo 6 — Criar schema SQLite e migrations
**Objetivo:** estabelecer persistência reproduzível, schema versionado e mapeamentos JPA compatíveis com SQLite antes das regras de negócio.
**Escopo realizado:** migration inicial V1 com tabelas para usuários, autorizações LinkedIn, transações OAuth, rascunhos, publicações, oportunidades e auditoria; entidades e repositórios JPA correspondentes; UUID/timestamps em `TEXT`; Hibernate em `validate`.
**Adições benéficas:** conversor de `Instant` para ISO-8601 e leitura de epoch-milliseconds legados; teste de persistência força leitura após limpar o contexto JPA; foreign keys SQLite são ativadas nos perfis local/test. O Passo 7 adicionou V2 para credenciais locais sem reescrever V1.
**Verificação:** Flyway e validação Hibernate são exercitados na inicialização de testes com SQLite em memória; persistência UUID/Instant de `AppUser` tem teste explícito e a suíte cobre a ativação de foreign keys.
**Pendências:** ainda não há teste de atualização de um banco V1 já populado para V2 nem teste de persistência individual de todas as entidades. Antes de alterar tabelas OAuth no Passo 8, comparar DDL, entidades e constraints e adicionar apenas migration aditiva, provando tanto banco vazio quanto upgrade preservando dados. O Passo 6 inicializou estruturas de funcionalidades futuras, mas não implementou tais funcionalidades; não expandir seus modelos até a etapa de negócio correspondente.

### Passo 7 — Implementar identidade local e autorização da aplicação
**Objetivo:** permitir que múltiplos usuários locais se cadastrem e autentiquem antes de conectar contas LinkedIn, com sessões independentes. O isolamento de rascunhos, publicações e demais recursos só poderá ser implementado quando esses casos de uso existirem nas etapas seguintes.
**Decisão:** múltiplas contas; e-mail normalizado; senha em BCrypt; sessão HTTP com CSRF; nenhuma senha ou token de sessão no corpo da resposta.
**Escopo implementado:** cadastro, login, logout, `/users/me`, `AppUserDetailsService`/principal, regras Security, aplicação da estratégia de troca do identificador de sessão no login, V2 para `password_hash` e índice case-insensitive; status e hash persistidos. Isso autentica usuários locais, mas ainda não implementa autorização de propriedade para rascunhos/publicações nem conexão LinkedIn. O início do OAuth no Passo 8 deve usar essa identidade local, sem confundi-la com a identidade LinkedIn.
**Adições benéficas:** testes unitários e de integração separados; validação do limite BCrypt em bytes UTF-8; token CSRF real obtido da API em teste; testes de isolamento entre sessões e ausência de credenciais nas respostas; exceções organizadas em `exception`; `UserRegistrationPort` movida para `application.ports.out`; remoção de `package-info.java` das pastas que agora têm classes.
**Verificação atual:** `mvn clean test` passou com 21 testes: 13 de API/autenticação, 4 unitários de cadastro, 2 de conversão, 1 de persistência e 1 de contexto. A integração comprova que duas sessões autenticadas consultam usuários distintos e que uma sessão não autenticada recebe `401`; isso não é teste de acesso a recursos de negócio. A ausência de `password`/`passwordHash` é verificada explicitamente na resposta de cadastro; o DTO comum de usuário também é usado no login, mas esse mesmo assert não é repetido nesse endpoint. Embora o login invoque `ChangeSessionIdAuthenticationStrategy`, ainda falta teste com sessão pré-autenticação para comprovar a rotação contra session fixation. Ver `docs/progress/STEP-07.md`.
**Limitações registradas ao concluir o Passo 7:** usuários anteriores à V2 recebem hash vazio e não conseguem autenticar; ainda não há redefinição/alteração de senha ou rate limiting (entregue posteriormente para autenticação e OAuth). A migration V2 ainda precisa de verificação explícita em banco V1 populado. Não declarar isolamento de rascunhos/publicações: esses casos de uso ainda não existem.

### Revisão retrospectiva dos Passos 1–7

- Os relatórios disponíveis são `STEP-01`, `STEP-02`, `STEP-02b`, `STEP-04`, `STEP-04b`, `STEP-05`, `STEP-06` e `STEP-07`. Não há relatório `STEP-03` nem evidência do Developer Portal; essa etapa manual permanece não confirmada.
- Melhorias posteriores ao roteiro curto original — conversão ISO/legado de `Instant`, foreign keys habilitadas por conexão, migração case-insensitive de e-mail, autenticação multiusuário, CSRF/sessão, testes unitários e integração, e organização explícita de ports/exceções — foram incorporadas às descrições dos Passos 5–7 acima.
- A alteração registrada em V1 no commit `2d50ec5` removeu somente uma linha em branco e não mudou schema nem comportamento; é ruído de formatação, não uma funcionalidade adicional.
- O status histórico de build em `STEP-04.md` antecede a execução posterior bem-sucedida; esta seção registra a validação mais recente sem reescrever o relato histórico. Em contrapartida, não se infere que o app LinkedIn esteja registrado, que o perfil prod esteja pronto ou que migrations de upgrade estejam validadas.
- Antes do Passo 8: confirmar manualmente a conclusão do Passo 3; escolher JDK 21 para validação do baseline; decidir/formalizar a chave de cifragem; confirmar contrato de callback/reconexão conforme `STEP-08-PLAN.md`; e decidir se upgrade V1→V2 precisa ser provado antes de OAuth.

### Passo 8 — Implementar o ciclo OAuth com o LinkedIn
**Objetivo:** conectar e desconectar a conta LinkedIn do usuário local autenticado por Authorization Code Flow, sem expor credenciais ou tokens ao cliente.
**Estado:** implementação local concluída e `mvn clean test` aprovado; rate limiting para login, cadastro e endpoints OAuth também foi implementado. A configuração real do app no Developer Portal, o smoke test e a validação com JDK 21 ainda não foram confirmados; antes da exposição pública, validar Redis distribuído e a configuração confiável do IP no proxy. Ver detalhes em [`STEP-08.md`](progress/STEP-08.md) e [`STEP-08-PLAN.md`](progress/STEP-08-PLAN.md).
**Pré-requisitos para smoke test real:** confirmar no Developer Portal os produtos/scopes efetivamente liberados, cadastrar a redirect URI exata e configurar client ID, client secret e chave fora do Git. A URI HTTPS pode ser necessária para execução fora do localhost; não usar valores reais em testes ou documentação.
**Escopo:** início autenticado do fluxo; geração, persistência por hash, expiração e consumo atômico de `state`; tratamento do callback e de recusas do provedor; troca de código no backend; consulta mínima de identidade via UserInfo; associação à conta local que iniciou o fluxo; armazenamento cifrado da autorização; consulta sanitizada do estado da conexão; desconexão local; tratamento de expiração e falhas externas.
**Restrições:** callback nunca aceita identidade de usuário fornecida pelo cliente; código, `state`, access token e eventual refresh token não aparecem em logs ou respostas; tokens são cifrados antes de persistir; não presumir nem implementar refresh ou revogação remota sem confirmação oficial; não adicionar publicação, rascunhos, mídia ou chamadas da Posts API neste passo.
**Persistência:** avaliar primeiro as tabelas/entidades `oauth_transaction` e `linkedin_authorization` existentes; nunca reescrever V1/V2. Criar migration aditiva somente se uma lacuna necessária for demonstrada.
**Testes:** unitários de serviço, cliente OAuth e cifragem; integração HTTP do provedor simulada, SQLite e MockMvc para sucesso, recusa, erro, replay, consumo concorrente, isolamento, CSRF, consulta sem segredos e desconexão; nenhum teste usa credenciais nem rede reais.
**Decisões implementadas:** callback JSON sanitizado sem redirect ao frontend; callback público protegido pelo state vinculado ao usuário; reconexão substitui somente após sucesso completo; desconexão apaga autorização e estados pendentes localmente; chave Base64 de 32 bytes para AES-256-GCM validada antes do início; sem refresh automático ou revogação remota; nenhuma migration adicional necessária.
**Critério automatizado atingido:** testes determinísticos passam com `mvn clean test`; a integração real permanece não verificada até a configuração do app e o smoke test opcional no Developer Portal.

### Passo 9 — Implementar o adaptador de publicação
**Objetivo:** encapsular toda comunicação de publicação com o LinkedIn.

### Passo 10 — Implementar rascunhos, aprovação e idempotência
**Objetivo:** impedir publicação acidental ou duplicada.

### Passo 11 — Adicionar oportunidades locais sem scraping
**Objetivo:** apoiar busca de vagas sem automatizar o LinkedIn.

### Passo 12 — Finalizar a API REST e o contrato OpenAPI

### Passo 13 — Completar testes automatizados

### Passo 14 — Aplicar segurança, LGPD técnica e observabilidade

### Passo 15 — Dockerizar e documentar a execução

### Passo 16 — Revisão de portfólio e CI/CD, e validar migração experimental para PostgreSQL

## Checklist de passagem entre etapas

Uma etapa só é considerada concluída quando:
- O objetivo está demonstrado por teste ou evidência reproduzível
- O código compila e os testes relevantes passam
- O comportamento está documentado
- Não existem secrets ou dados reais no repositório
- Erros e limites conhecidos estão registrados
- Não há dependência indevida entre domínio, LinkedIn ou SQLite
- As migrations foram testadas em banco limpo
- O próximo agente consegue entender o estado atual só lendo README + planejamento + relatório da etapa

Cada agente deixa um registro em `docs/progress/STEP-XX.md`: objetivo, alterações, comandos, testes, decisões, pendências e próximo passo.

## Referências

1. LinkedIn User Agreement
2. Prohibited software and extensions — LinkedIn Help
3. Share on LinkedIn — Microsoft Learn
4. Getting Access to LinkedIn APIs — Microsoft Learn
5. Easy Apply limits — LinkedIn Help
