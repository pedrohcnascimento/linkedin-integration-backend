# linkedin-integration-backend

Backend próprio, em Java/Spring Boot, para integração **autorizada** com o LinkedIn via API oficial (OAuth 2.0 + produto **Share on LinkedIn**).

> ⚠️ Este projeto **não** faz scraping, automação de navegador ou qualquer ação fora dos endpoints oficiais documentados pelo LinkedIn. Ver [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md) para o racional completo e os limites deliberados de escopo.

## Status atual

🚧 **Passo 10 — rascunhos e publicação textual** em andamento. OAuth foi validado com o app no Developer Portal (`ACTIVE`). A API implementa criação, listagem paginada, edição, aprovação, publicação idempotente via Posts API e histórico paginado por usuário. Reservas são persistidas antes da chamada externa; resultados ambíguos bloqueiam novas tentativas até reconciliação manual. A concorrência ainda precisa de validação em Oracle de teste. Veja [`docs/status-atual.md`](docs/status-atual.md).

Acompanhe o progresso em [`docs/progress/`](docs/progress) e o roteiro, incluindo os próximos passos, em [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md#15-roteiro-operacional-cronológico-passo-a-passo-executável).

### Como interpretar a documentação

Para o **estado funcional e as pendências atuais**, prevalece [`docs/status-atual.md`](docs/status-atual.md); este README é o resumo voltado a quem usa o projeto. O planejamento técnico é a referência para o roteiro e os requisitos futuros, mas não comprova que uma etapa foi implementada. Para fatos e decisões sobre a API do LinkedIn, prevalece [`docs/linkedin-capability-matrix.md`](docs/linkedin-capability-matrix.md), inclusive quando houver divergência com o planejamento. Os relatórios em `docs/progress/` são registros históricos das etapas e decisões no momento em que foram escritos; não devem ser usados como fonte de pendências ou decisões vigentes.

## Stack técnica

| Item | Escolha |
|---|---|
| Linguagem | Java 21 (LTS) |
| Build | Maven |
| Framework | Spring Boot **4.1.0** (Webmvc, Security, Validation, Actuator) |
| Persistência | SQLite (local/testes); Oracle configurado no perfil `prod`, ainda sem validação em banco de teste |
| Autenticação externa | OAuth 2.0 + OpenID Connect com o LinkedIn |
| LinkedIn | OAuth 2.0 e publicação textual via **Posts API** implementados |
| Documentação de API | springdoc-openapi 3.x |
| Empacotamento | JAR executável (Spring Boot) |

> Nota de versão: o projeto usa Spring Boot 4.x porque a linha 3.5.x encerrou o suporte OSS em 25/06/2026. O Boot 4 trouxe starters modulares (`spring-boot-starter-webmvc` no lugar de `spring-boot-starter-web`, por exemplo) — se for comparar com tutoriais mais antigos, tenha isso em mente.

## Estado funcional

### Implementado

- Cadastro de contas locais, login por sessão, consulta do usuário autenticado e logout.
- Token CSRF para requisições que alteram estado.
- Conexão LinkedIn via OAuth 2.0, consulta sanitizada do estado da conexão e desconexão local. Tokens são cifrados em repouso e não são devolvidos pela API.
- Rate limiting para autenticação e endpoints OAuth.
- Rascunhos textuais: criação, listagem paginada, edição antes da aprovação, aprovação e publicação autenticada com `Idempotency-Key`.
- Adaptador da Posts API para publicação textual de membro; respostas externas não são devolvidas sem sanitização.

Essas funcionalidades são expostas pelos endpoints listados abaixo e cobertas por testes automatizados. O fluxo OAuth foi validado contra o app real no Developer Portal; o provedor continua sendo simulado na suíte automatizada.

### Em andamento / planejado

- Validação em Oracle de teste das migrations, do schema e da concorrência; a configuração atual não comprova compatibilidade operacional.
- Registro e organização local de oportunidades de vaga.

As tabelas ou migrations preliminares de oportunidades não significam que esse recurso esteja executável. O histórico de publicações tem endpoint paginado; não há endpoints de oportunidades.

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
| `POST /api/v1/drafts` | Sessão + CSRF | Cria um rascunho textual |
| `GET /api/v1/drafts?page=0&size=20` | Sessão | Lista os rascunhos do usuário; página de 0 a 50 itens |
| `PATCH /api/v1/drafts/{id}` | Sessão + CSRF | Edita texto/título enquanto o rascunho está em `DRAFT` |
| `POST /api/v1/drafts/{id}/approve` | Sessão + CSRF | Aprova o rascunho |
| `POST /api/v1/drafts/{id}/publish` | Sessão + CSRF | Publica rascunho aprovado; exige `Idempotency-Key` |
| `GET /api/v1/publications?page=0&size=20` | Sessão | Histórico de publicações do usuário; tamanho máximo 50 |

`GET /actuator/health` também está disponível para health check. A documentação interativa da API (`/swagger-ui.html`) e o OpenAPI (`/api-docs`) são habilitados apenas nos perfis `local` e `test`; no perfil `prod`, ambos ficam desativados.

## Próximos passos

1. Concluir o Passo 10: validar concorrência, migrations e schema em Oracle descartável e documentar a rotina de reconciliação manual. O contrato OpenAPI de publicação e histórico já está revisado; resultado ambíguo já bloqueia novos envios até reconciliação manual.
2. Confirmar JDK 21, Redis e configuração do IP atrás do proxy antes de exposição pública. A presença de dependências PostgreSQL no Maven não significa que PostgreSQL seja o banco configurado no perfil `prod`.
3. Depois de fechar o Passo 10, confirmar prioridade e escopo de oportunidades locais para iniciar o Passo 11.

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

O perfil `local` usa SQLite e o perfil `prod` está configurado para Oracle, com driver e migration Oracle do Flyway e Hibernate `ddl-auto=validate` (não cria nem atualiza tabelas). Essa configuração não comprova que o schema tenha sido aplicado ou validado em um serviço Oracle; faça essa validação em banco de teste descartável antes de considerar o perfil pronto para produção. A URL deve usar o formato Oracle aceito pelo ambiente, por exemplo `jdbc:oracle:thin:@<host>:1521:<service-name>`.

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

Login é limitado por IP (30 tentativas/15 min) e conta (8/15 min); cadastro por IP (10/h) e endereço de conta (3/24 h); início do OAuth por IP (30/15 min), usuário (10/h) e sessão (8/15 min); callback por IP (60/15 min) e state (6/15 min). A publicação de draft é limitada por IP (30/15 min), usuário (10/15 min) e sessão (10/15 min). Os contadores usam janelas fixas iniciadas na primeira tentativa. A resposta de limite é `429 RATE_LIMITED`, genérica, com `Retry-After` em segundos.

No perfil `prod`, Redis é obrigatório e compartilhado por todas as instâncias. Scripts Lua incrementam atomicamente os contadores e as chaves expiram ao fim das janelas. Configure `RATE_LIMIT_REDIS_URL` com autenticação e TLS quando disponível, e use o mesmo `RATE_LIMIT_HMAC_KEY` Base64 de pelo menos 32 bytes em todas as instâncias (por exemplo, gere-o com `openssl rand -base64 32`); os valores usados nas chaves são HMACs, não IPs, e-mails, sessões ou state em claro. Se Redis estiver indisponível, as requisições limitadas falham fechadas com `503` e `Retry-After: 5`. Os perfis `local` e `test` usam armazenamento em memória, sem garantia entre reinícios ou instâncias, portanto não devem ser usados como mecanismo de produção distribuído.

O servidor usa `getRemoteAddr()` e ignora `Forwarded`/`X-Forwarded-For` para evitar falsificação pelo cliente. Atrás de proxy, preserve o IP de origem até a aplicação ou configure o rate limiting no gateway confiável; não encaminhe headers fornecidos diretamente pelo cliente como se fossem confiáveis.

O Maven declara também dependências PostgreSQL, mas `application-prod.yml` seleciona Oracle, as migrations de produção disponíveis são Oracle e não há teste `PostgreSqlProfileIntegrationTest` no código atual. Portanto, não use o antigo comando PostgreSQL como evidência de validação do perfil `prod`. O teste automatizado do banco de produção ainda precisa ser criado/ajustado para o banco efetivamente escolhido e executado em uma instância descartável.

Os testes atuais exercitam os limites com armazenamento simulado/em memória; não há teste de integração Redis no código-fonte presente. Valide Redis em ambiente descartável antes de depender dele em produção.

### Autenticação local

O cliente deve guardar o cookie de sessão com segurança e enviar o token CSRF no header indicado pela rota `/csrf`; tokens de senha nunca são devolvidos pela API. Em produção, a aplicação deve ser publicada exclusivamente por HTTPS.

### Conexão com o LinkedIn

No perfil `local`, a ausência de `TOKEN_ENCRYPTION_KEY` não impede a aplicação de iniciar; a chave é validada ao iniciar o OAuth e a operação retorna erro controlado se estiver ausente ou inválida. Configure uma chave Base64 que decodifique para exatamente 32 bytes antes de conectar o LinkedIn. O valor nunca deve ser registrado ou versionado.

O callback está configurado como `http://localhost:8080/api/v1/linkedin/oauth/callback` apenas para desenvolvimento local. Em produção, configure a URI HTTPS exata também no Developer Portal. Os scopes padrão são `openid profile w_member_social`; os scopes efetivamente concedidos dependem dos produtos habilitados no app. O `state` OAuth é consumido antes da troca do código e das chamadas externas; se troca, UserInfo, cifragem ou persistência falhar, será necessário iniciar uma nova autorização, pois o callback não pode ser repetido. Nenhum teste automatizado chama o LinkedIn real. A desconexão remove credenciais locais, mas não afirma revogação remota.

`CORS_ALLOWED_ORIGINS` aceita uma lista separada por vírgulas de origens HTTP/HTTPS exatas; espaços em torno de cada origem são ignorados e entradas vazias são descartadas. URLs com caminho, credenciais, query, fragmento ou wildcard são inválidas. Como as sessões usam credenciais, não configure `*`.

## Limitações atuais

- Oportunidades ainda não têm endpoints funcionais.
- Não há refresh token presumido — depende do que o LinkedIn efetivamente conceder para o app registrado.
- Falhas conhecidas são registradas como `FAILED`; resultados ambíguos ficam em `OUTCOME_UNKNOWN` ou permanecem `PUBLISHING` após interrupção. Ambos bloqueiam novo envio até reconciliação manual; não existe retry automático nem endpoint de reconciliação.
- O perfil `prod` está configurado para Oracle, mas sua migration/schema não têm teste de integração Oracle no repositório.

## Contribuindo

Veja [`CONTRIBUTING.md`](CONTRIBUTING.md) — inclui as regras que qualquer agente de IA (ou pessoa) deve seguir antes de alterar este repositório.

## Segurança

Veja [`SECURITY.md`](SECURITY.md).

## Licença

MIT — veja [`LICENSE`](LICENSE).
