# linkedin-integration-backend

Backend próprio, em Java/Spring Boot, para integração **autorizada** com o LinkedIn via API oficial (OAuth 2.0 + produto **Share on LinkedIn**).

> ⚠️ Este projeto **não** faz scraping, automação de navegador ou qualquer ação fora dos endpoints oficiais documentados pelo LinkedIn. Ver [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md) para o racional completo e os limites deliberados de escopo.

## Status atual

🚧 **Passo 10 — rascunho aprovado e idempotência** em andamento. O ciclo OAuth do Passo 8 está concluído e validado com o app real no Developer Portal (`ACTIVE`). A API já permite criar, listar, editar, aprovar e publicar rascunhos textuais com `Idempotency-Key`; histórico e mídia ainda não estão disponíveis. Ver [`docs/progress/STEP-10.md`](docs/progress/STEP-10.md).

Acompanhe o progresso em [`docs/progress/`](docs/progress) e o roteiro, incluindo os próximos passos, em [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md#15-roteiro-operacional-cronológico-passo-a-passo-executável).

### Como interpretar a documentação

Para o **estado funcional e as pendências atuais**, prevalece [`docs/status-atual.md`](docs/status-atual.md); este README é o resumo voltado a quem usa o projeto. O planejamento técnico é a referência para o roteiro e os requisitos futuros, mas não comprova que uma etapa foi implementada. Para fatos e decisões sobre a API do LinkedIn, prevalece [`docs/linkedin-capability-matrix.md`](docs/linkedin-capability-matrix.md), inclusive quando houver divergência com o planejamento. Os relatórios em `docs/progress/` são registros históricos das etapas e decisões no momento em que foram escritos; não devem ser usados como fonte de pendências ou decisões vigentes.

## Stack técnica

| Item | Escolha |
|---|---|
| Linguagem | Java 21 (LTS) |
| Build | Maven |
| Framework | Spring Boot **4.1.0** (Webmvc, Security, Validation, Actuator) |
| Persistência | SQLite (local/testes) e PostgreSQL (produção), via JPA/Hibernate 7 + Flyway |
| Autenticação externa | OAuth 2.0 + OpenID Connect com o LinkedIn |
| LinkedIn | OAuth 2.0 e consulta de conexão implementados; publicação via **Posts API** planejada, ainda não implementada |
| Documentação de API | springdoc-openapi 3.x |
| Empacotamento | JAR executável (Spring Boot) |

> Nota de versão: o projeto usa Spring Boot 4.x porque a linha 3.5.x encerrou o suporte OSS em 25/06/2026. O Boot 4 trouxe starters modulares (`spring-boot-starter-webmvc` no lugar de `spring-boot-starter-web`, por exemplo) — se for comparar com tutoriais mais antigos, tenha isso em mente.

## Estado funcional

### Implementado

- Cadastro de contas locais, login por sessão, consulta do usuário autenticado e logout.
- Token CSRF para requisições que alteram estado.
- Conexão LinkedIn via OAuth 2.0, consulta sanitizada do estado da conexão e desconexão local. Tokens são cifrados em repouso e não são devolvidos pela API.
- Rate limiting para autenticação e endpoints OAuth.

Essas funcionalidades são expostas pelos endpoints listados abaixo e cobertas por testes automatizados. O fluxo OAuth foi validado contra o app real no Developer Portal; o provedor continua sendo simulado na suíte automatizada.

### Planejado, ainda não implementado

- Criação, listagem paginada, edição, aprovação e publicação textual de rascunho usando a Posts API, com idempotência por `Idempotency-Key`.
- Casos de uso e endpoints para criar/revisar rascunhos, aprovar e publicar conteúdo, com histórico e idempotência.
- Registro e organização local de oportunidades de vaga.

As tabelas ou migrations preliminares existentes não significam que esses recursos estejam executáveis. **Não existem endpoints de rascunhos, publicações/histórico ou oportunidades**; os caminhos desses futuros endpoints ainda não estão definidos.

### Fora do escopo

- Não automatiza login, navegação ou cliques no LinkedIn
- Não usa cookies de sessão do LinkedIn
- Não faz scraping de perfis, vagas ou resultados de busca
- Não envia convites, mensagens ou candidaturas automaticamente
- Não usa Easy Apply de forma automatizada

## Endpoints disponíveis

Todas as rotas de aplicação usam o prefixo `/api/v1`. Rotas protegidas exigem sessão local; requisições que alteram estado também exigem CSRF.

| Método e caminho | Acesso | Função |
|---|---|---|
| `GET /api/v1/auth/csrf` | Público | Obtém o token CSRF e o nome do header |
| `POST /api/v1/auth/register` | Público | Cria uma conta local |
| `POST /api/v1/auth/login` | Público | Autentica e inicia uma sessão |
| `POST /api/v1/auth/logout` | Sessão + CSRF | Encerra a sessão (`204`) |
| `GET /api/v1/users/me` | Sessão | Consulta a conta autenticada |
| `GET /api/v1/linkedin/oauth/start` | Sessão | Inicia o fluxo OAuth e redireciona ao LinkedIn |
| `GET /api/v1/linkedin/oauth/callback` | Público, protegido por `state` de uso único | Conclui o OAuth e retorna estado sanitizado |
| `GET /api/v1/linkedin/connection` | Sessão | Consulta o estado da conexão LinkedIn |
| `DELETE /api/v1/linkedin/connection` | Sessão + CSRF | Remove a autorização local (`204`) |

`GET /actuator/health` também está disponível para health check. A documentação interativa da API (`/swagger-ui.html`) e o OpenAPI (`/api-docs`) são habilitados apenas nos perfis `local` e `test`; no perfil `prod`, ambos ficam desativados.

## Próximos passos

1. Validar o ciclo OAuth com o app configurado no Developer Portal e executar smoke test; validar também com JDK 21 e, antes de exposição pública, Redis distribuído e IP confiável no proxy.
2. Seguir o planejamento: adaptador de publicação (Passo 9), rascunhos/aprovação/idempotência (Passo 10) e oportunidades locais (Passo 11).
3. Completar endpoints e contrato OpenAPI, testes, segurança e observabilidade conforme os Passos 12–14. Nenhum desses itens deve ser considerado disponível até estar implementado e testado.

## Arquitetura

Hexagonal leve (ports & adapters): domínio isolado de Spring, JPA e do LinkedIn. Detalhes completos na seção 4 de [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md#4-arquitetura-proposta).

```
com.example.linkedinagent
├── domain        # regras de negócio, sem dependência de framework
├── application    # casos de uso (ports/in, ports/out)
├── adapter
│   ├── in/web     # controllers REST
│   └── out        # LinkedIn HTTP client, persistência JPA, segurança
├── config
├── exception
└── shared
```

## Como rodar

Este repositório **não inclui o Maven Wrapper** (`mvnw`/`mvnw.cmd`) ainda — gerado no ambiente onde o código foi escrito, sem acesso ao Maven Central para validá-lo. Use o Maven do seu sistema ou o integrado ao IntelliJ:

```bash
# build + testes
mvn clean test

# subir localmente
mvn spring-boot:run
```

No IntelliJ: abra o projeto como Maven (`pom.xml`), deixe indexar as dependências, e rode `LinkedinAgentApplication` ou a suíte de testes pela própria IDE.

Para habilitar a documentação somente em desenvolvimento, execute com `SPRING_PROFILES_ACTIVE=local` (esse já é o perfil padrão). Não habilite as propriedades `springdoc.api-docs.enabled` ou `springdoc.swagger-ui.enabled` em produção.

Depois de validar que builda, você pode gerar o wrapper com `mvn -N wrapper:wrapper` (ou `mvn wrapper:wrapper` na raiz) para passar a usar `./mvnw` — mais consistente entre máquinas.

Variáveis de ambiente necessárias (ver `.env.example`):

```
LINKEDIN_CLIENT_ID
LINKEDIN_CLIENT_SECRET
LINKEDIN_REDIRECT_URI
LINKEDIN_SCOPES
TOKEN_ENCRYPTION_KEY
APP_BASE_URL
DATABASE_URL
DATABASE_USERNAME
DATABASE_PASSWORD
CORS_ALLOWED_ORIGINS
RATE_LIMIT_HMAC_KEY
RATE_LIMIT_REDIS_URL (somente no perfil prod)
```

Nenhuma dessas variáveis deve conter valores reais no repositório.

### Oracle em produção

O perfil `local` usa SQLite e o perfil `prod` usa o driver Oracle empacotado pela aplicação. Em produção, a aplicação executa as migrations Oracle do Flyway e inicia o Hibernate com `ddl-auto=validate` (não cria nem atualiza tabelas). A URL deve usar o formato Oracle aceito pelo ambiente, por exemplo `jdbc:oracle:thin:@oracle.fiap.com.br:1521:ORCL`.

Configure no ambiente de execução, sem versionar credenciais:

```text
SPRING_PROFILES_ACTIVE=prod
DATABASE_URL=jdbc:oracle:thin:@<host>:1521:<service-name>
DATABASE_USERNAME=<usuario do banco>
DATABASE_PASSWORD=<senha do banco>
LINKEDIN_CLIENT_ID=<client id>
LINKEDIN_CLIENT_SECRET=<client secret>
LINKEDIN_REDIRECT_URI=https://<dominio>/api/v1/linkedin/oauth/callback
TOKEN_ENCRYPTION_KEY=<Base64 de exatamente 32 bytes>
RATE_LIMIT_HMAC_KEY=<Base64 de pelo menos 32 bytes, gere um segredo exclusivo para prod>
RATE_LIMIT_REDIS_URL=rediss://:<senha>@<redis-host>:6379
APP_BASE_URL=https://<dominio>
CORS_ALLOWED_ORIGINS=https://<frontend>
```

`LINKEDIN_SCOPES` é opcional e usa `openid profile w_member_social` por padrão. Cadastre a redirect URI exata no Developer Portal. Para iniciar com Maven, execute `mvn spring-boot:run` com essas variáveis no ambiente; para um JAR empacotado, use `java -jar target/linkedinagent-0.1.0-SNAPSHOT.jar`. A conta do banco precisa poder criar/alterar objetos para aplicar migrations na primeira inicialização e ler o histórico Flyway nas seguintes.

#### Rate limiting

Login é limitado por IP (30 tentativas/15 min) e conta (8/15 min); cadastro por IP (10/h) e endereço de conta (3/24 h); início do OAuth por IP (30/15 min), usuário (10/h) e sessão (8/15 min); callback por IP (60/15 min) e state (6/15 min). Os contadores usam janelas fixas iniciadas na primeira tentativa. A resposta de limite é `429 RATE_LIMITED`, genérica, com `Retry-After` em segundos. Publicações ainda não têm rotas; o interceptor já aplica limites por IP, usuário e sessão em futuras operações de escrita sob `/api/v1/publications/**`.

No perfil `prod`, Redis é obrigatório e compartilhado por todas as instâncias. Scripts Lua incrementam atomicamente os contadores e as chaves expiram ao fim das janelas. Configure `RATE_LIMIT_REDIS_URL` com autenticação e TLS quando disponível, e use o mesmo `RATE_LIMIT_HMAC_KEY` Base64 de pelo menos 32 bytes em todas as instâncias (por exemplo, gere-o com `openssl rand -base64 32`); os valores usados nas chaves são HMACs, não IPs, e-mails, sessões ou state em claro. Se Redis estiver indisponível, as requisições limitadas falham fechadas com `503` e `Retry-After: 5`. Os perfis `local` e `test` usam armazenamento em memória, sem garantia entre reinícios ou instâncias, portanto não devem ser usados como mecanismo de produção distribuído.

O servidor usa `getRemoteAddr()` e ignora `Forwarded`/`X-Forwarded-For` para evitar falsificação pelo cliente. Atrás de proxy, preserve o IP de origem até a aplicação ou configure o rate limiting no gateway confiável; não encaminhe headers fornecidos diretamente pelo cliente como se fossem confiáveis.

Para validar a configuração de produção contra um banco PostgreSQL de teste vazio e descartável, defina `POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME` e `POSTGRES_TEST_PASSWORD` e execute `mvn -Dtest=PostgreSqlProfileIntegrationTest test`. Esse teste sobe o perfil `prod`, aplica as migrations, confirma a conexão e `ddl-auto=validate`, e exercita persistência/consulta de usuário, autorização LinkedIn e consumo de transação OAuth. Não aponte essas variáveis para um banco de produção: o teste grava dados. Sem `POSTGRES_TEST_URL`, o teste é ignorado e a suíte normal continua usando SQLite.

Para exercitar o armazenamento distribuído, configure `RATE_LIMIT_REDIS_TEST_URL` para um Redis de teste descartável e execute `mvn -Dtest=RedisRateLimitStoreIntegrationTest test`. Sem essa variável, esses testes de integração são ignorados.

### Autenticação local

O cliente deve guardar o cookie de sessão com segurança e enviar o token CSRF no header indicado pela rota `/csrf`; tokens de senha nunca são devolvidos pela API. Em produção, a aplicação deve ser publicada exclusivamente por HTTPS.

### Conexão com o LinkedIn

O callback está configurado como `http://localhost:8080/api/v1/linkedin/oauth/callback` apenas para desenvolvimento local. Em produção, configure a URI HTTPS exata também no Developer Portal. Os scopes padrão são `openid profile w_member_social`; os scopes efetivamente concedidos dependem dos produtos habilitados no app. Nenhum teste automatizado chama o LinkedIn real. A desconexão remove credenciais locais, mas não afirma revogação remota.

## Limitações atuais

- Publicação, rascunhos, histórico e oportunidades ainda não têm casos de uso nem endpoints funcionais.
- Não há refresh token presumido — depende do que o LinkedIn efetivamente conceder para o app registrado.
- O perfil PostgreSQL de produção está configurado; antes do deploy, valide as migrations e o schema em um PostgreSQL de teste compatível com a versão do serviço.

## Contribuindo

Veja [`CONTRIBUTING.md`](CONTRIBUTING.md) — inclui as regras que qualquer agente de IA (ou pessoa) deve seguir antes de alterar este repositório.

## Segurança

Veja [`SECURITY.md`](SECURITY.md).

## Licença

MIT — veja [`LICENSE`](LICENSE).
