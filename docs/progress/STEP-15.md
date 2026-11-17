# STEP-15 — Dockerizar e documentar a execução e o desenvolvimento do projeto

**Status:** concluído em 16/11/2026.  
**Base metodológica:** [`docs/planejamento-tecnico.md`](../planejamento-tecnico.md) (seção 15 — Passo 15), [`docs/status-atual.md`](../status-atual.md) e [`README.md`](../../README.md).

## 1. Objetivo da etapa

Executar e consolidar o **Passo 15 — Dockerizar e documentar a execução**, abrangendo:
1. Criação de imagem Docker multi-stage reproduzível (`JDK 21` no build e `JRE 21` no runtime), executando com usuário não-root (`appuser`), `HEALTHCHECK` apontando para `/actuator/health` e isolamento estrito via `.dockerignore` para impedir que `.env`, chaves ou arquivos SQLite locais (`data/*.db`) entrem na imagem.
2. Orquestração local via `docker-compose.yml` com volume nomeado para persistência SQLite no perfil `local` e serviço `redis` opcional (`--profile prod-local`) para testes do limitador de taxa distribuído.
3. Correção do carregamento automático do arquivo `.env` no bootstrap da aplicação (`LinkedinAgentApplication.loadDotEnvIfPresent()`) para compatibilidade nativa com **Spring Boot 4.1.2** (sem depender de `META-INF/spring.factories` removido no upgrade do `spring-dotenv`) e desativação do scan desnecessário de repositórios Spring Data Redis (`spring.data.redis.repositories.enabled: false`).
4. Documentação consolidada do desenvolvimento completo do projeto (Passos 1 a 15), arquitetura DDD, modelo de segurança e instruções de execução local, via Docker e em nuvem.

---

## 2. Consolidação do Desenvolvimento do Projeto (Passos 1 a 15)

A tabela abaixo resume a trajetória completa de engenharia do `linkedin-integration-backend`, desde a concepção ética e congelamento de escopo até a conteinerização:

| Passo | Tema | Entrega Principal e Decisões Arquiteturais | Registro |
|---|---|---|---|
| **Passo 1** | Escopo ético e conformidade | Definição estrita de uso exclusivo da API oficial do LinkedIn; proibição de scraping, cookies de sessão ou automação de navegador. | [`STEP-01.md`](STEP-01.md) |
| **Passo 2** | Matriz de capacidades LinkedIn | Congelamento do escopo v1 na **Posts API** (`POST /rest/posts`, cabeçalho `Linkedin-Version: 202609`) e **OpenID Connect** (`/v2/userinfo`), descartando a UGC Post API legada. | [`STEP-02.md`](STEP-02.md), [`STEP-02b.md`](STEP-02b.md) |
| **Passo 3** | Registro no Developer Portal | Parametrização externa de `LINKEDIN_CLIENT_ID`, `LINKEDIN_CLIENT_SECRET`, `LINKEDIN_REDIRECT_URI` e scopes (`openid profile w_member_social`) sem expor credenciais no Git. | [`.env.example`](../../.env.example) |
| **Passo 4** | Esqueleto Spring Boot 4 + Java 21 | Inicialização modular com Spring Boot 4 (`webmvc`, `security`, `validation`, `data-jpa`, `flyway`, `actuator`), `ddl-auto: validate` e health check `/actuator/health`. | [`STEP-04.md`](STEP-04.md), [`STEP-04b.md`](STEP-04b.md) |
| **Passo 5** | Perfis de ambiente e segredos | Separação de `application.yml` (default fail-safe `prod`), `application-local.yml` (SQLite + Swagger), `application-test.yml` (SQLite in-memory) e `application-prod.yml` (Oracle + Redis + cookies `Secure`), tipados em `AppProperties`. | [`STEP-05.md`](STEP-05.md) |
| **Passo 6** | Schema SQLite e Migrations Flyway | Criação da migration base `V1__initial_schema.sql` (tabelas `app_user`, `linkedin_authorization`, `oauth_transaction`, `content_draft`, `publication`, `opportunity`, `audit_event`) e conversores `Instant`/`UUID` com `PRAGMA foreign_keys=ON`. | [`STEP-06.md`](STEP-06.md) |
| **Passo 7** | Identidade local e sessão segura | Cadastro (`POST /api/v1/auth/register`), login (`POST /api/v1/auth/login`), logout (`POST /api/v1/auth/logout`), `/users/me`, senhas com `BCrypt`, migration `V2__app_user_local_credentials.sql`, proteção contra *Session Fixation* e CSRF. | [`STEP-07.md`](STEP-07.md) |
| **Passo 8** | Ciclo OAuth 2.0 com LinkedIn | Início autenticado (`/oauth/start`), `state` de 256 bits persistido por hash SHA-256 com consumo atômico, callback vinculado à sessão autenticada, cifragem **AES-256-GCM** de tokens em repouso (`AesGcmTokenCipher`) e consulta/desconexão sanitizada. | [`STEP-08.md`](STEP-08.md) |
| **Passo 9** | Adaptador HTTP da Posts API | Cliente isolado `LinkedInPublishingHttpClient` para `POST /rest/posts` com extração de `x-restli-id`, tratamento defensivo de `429`/`Retry-After` e sanitização de erros externos. | [`STEP-09.md`](STEP-09.md) |
| **Passo 10** | Rascunhos, aprovação e idempotência | Fluxo de criação, listagem paginada, edição em `DRAFT`, aprovação explícita (`APPROVED`) e publicação idempotente (`Idempotency-Key`), reserva transacional `PUBLISHING` com lock pessimista, estado `OUTCOME_UNKNOWN` para falhas ambíguas e runbook de reconciliação manual. | [`STEP-10.md`](STEP-10.md) |
| **Passo 11** | Oportunidades locais (adiado) | Adiamento formal da funcionalidade de vagas/oportunidades para ciclo pós-estabilização da v1, mantendo o foco em publicação textual segura. | [`STEP-11.md`](STEP-11.md) |
| **Passo 12** | Contrato REST e OpenAPI 3 | Padronização do contrato de erro (`ApiError`), documentação completa das 15 operações `/api/v1/**` no perfil `local`, ocultação em `prod` e verificação automatizada de paridade de rotas. | [`STEP-12.md`](STEP-12.md) |
| **Passo 13** | Suíte de testes em todas as camadas | Consolidação de testes unitários e de integração cobrindo domínio, DTOs, clientes HTTP (`MockRestServiceServer`), cifragem AES-GCM, rate limiters, upgrade Flyway `V1 -> V2` e isolamento multiusuário. | [`STEP-13.md`](STEP-13.md) |
| **Passo 14** | Segurança, LGPD e observabilidade | Trilha `audit_event` sanitizada (`AuditService`), retenção e exclusão de conta LGPD (`DataRetentionService` + `DELETE /api/v1/users/me`), correlação `X-Request-Id` no MDC, headers HSTS/CSP/`Referrer-Policy`/`Permissions-Policy`, timeout absoluto de sessão (8h), AAD por usuário no AES-GCM e workflows de CI de segurança. | [`STEP-14.md`](STEP-14.md) |
| **Passo 15** | Dockerização e guia de operação | `Dockerfile` multi-stage não-root com `HEALTHCHECK`, `.dockerignore`, `docker-compose.yml`, carregamento nativo de `.env` no bootstrap do Spring Boot 4.1.2 e documentação ponta a ponta. | Este documento |

---

## 3. Alterações implementadas no Passo 15

### 3.1. Artefatos de Conteinerização
- **[`Dockerfile`](../../Dockerfile):**
  - **Estágio de build (`maven:3.9.9-eclipse-temurin-21`):** resolve dependências (`mvn -B dependency:go-offline`) e gera o JAR executável (`mvn -B clean package -DskipTests`) sem copiar arquivos `.env` ou bancos locais.
  - **Estágio de runtime (`eclipse-temurin:21-jre`):** cria grupo/usuário de sistema sem privilégios (`appuser`, UID/GID `10001`), prepara `/app/data` com permissão para montagem de volume SQLite quando usado no perfil `local`, define `HEALTHCHECK` consultando `http://localhost:8080/actuator/health` e inicia a JVM com `-XX:MaxRAMPercentage=75.0`.
- **[`.dockerignore`](../../.dockerignore):**
  - Bloqueia o envio de `.env`, `.env.*` (exceto `.env.example`), `data/*.db`, `data/*.db-wal`, `data/*.db-shm`, `target/`, `.git/` e `.idea/` para o contexto de build do Docker.
- **[`docker-compose.yml`](../../docker-compose.yml):**
  - Define o serviço `app` lendo variáveis locais do `.env` em tempo de execução (sem embuti-las na imagem), expondo a porta `8080` e montando o volume nomeado `sqlite_data:/app/data`.
  - Define o serviço opcional `redis` (`redis:7.4-alpine`) sob o profile `prod-local` para testes locais com rate limiting distribuído.

### 3.2. Ajustes de Inicialização e Configuração (Spring Boot 4.1.2)
- **[`LinkedinAgentApplication.java`](../../src/main/java/com/example/linkedinagent/LinkedinAgentApplication.java):**
  - Adicionado método `loadDotEnvIfPresent()` antes de `SpringApplication.run(...)`. Com o upgrade para Spring Boot 4.1.2 e `spring-dotenv 5.1.0`, o arquivo `.env` não era carregado antes da resolução de `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:prod}`. A leitura nativa garante que variáveis definidas no `.env` (como `SPRING_PROFILES_ACTIVE=local` e `LINKEDIN_CLIENT_ID`) sejam aplicadas sem sobrescrever variáveis reais de ambiente do sistema operacional ou de containers (`System.getenv()` e `-D` têm precedência).
- **[`src/main/resources/application.yml`](../../src/main/resources/application.yml):**
  - Adicionado `spring.data.redis.repositories.enabled: false` para evitar que o Spring Data Redis tente inspecionar as interfaces JPA (`AppUserRepository`, `ContentDraftRepository`, etc.) durante o bootstrap, já que o uso do Redis no projeto ocorre exclusivamente via `StringRedisTemplate` em `RedisRateLimitStore`.

---

## 4. Como construir e executar (Local, Docker e Nuvem)

### 4.1. Execução Local (Maven / IntelliJ)
1. Copie `.env.example` para `.env` e mantenha `SPRING_PROFILES_ACTIVE=local`.
2. Execute a compilação ou inicialização:
   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\jbr'
   & "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.4\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" spring-boot:run
   ```
3. Acesse:
   - Health check: `http://localhost:8080/actuator/health`
   - Swagger UI (apenas perfil `local`): `http://localhost:8080/swagger-ui.html`

### 4.2. Execução via Docker / Docker Compose
1. **Subir com Docker Compose (perfil `local` com volume SQLite):**
   ```bash
   docker compose up --build
   ```
2. **Build e execução manual da imagem:**
   ```bash
   docker build -t linkedin-integration-backend:0.1.0 .
   docker run --rm -p 8080:8080 \
     --env-file .env \
     -e SPRING_PROFILES_ACTIVE=local \
     -v linkedin_sqlite_data:/app/data \
     linkedin-integration-backend:0.1.0
   ```

### 4.3. Deploy em Nuvem e Domínio/URL Pública
- **Não é necessário comprar um domínio próprio para fazer deploy:** plataformas PaaS/Containers (Render, Railway, Fly.io, Google Cloud Run, Azure Container Apps) fornecem automaticamente um subdomínio HTTPS público (ex.: `https://<app>.onrender.com` ou `https://<app>.up.railway.app`).
- Basta configurar a URL HTTPS gerada pela plataforma nas variáveis de ambiente do container:
  - `APP_BASE_URL=https://<sua-url-da-plataforma>`
  - `LINKEDIN_REDIRECT_URI=https://<sua-url-da-plataforma>/api/v1/linkedin/oauth/callback`
  - `CORS_ALLOWED_ORIGINS=https://<seu-frontend>`
- Em seguida, cadastre exatamente o mesmo endereço de `LINKEDIN_REDIRECT_URI` em **Authorized redirect URLs for your app** no LinkedIn Developer Portal.
- **Escolha do perfil no deploy:**
  - Use `SPRING_PROFILES_ACTIVE=prod` quando o banco **Oracle** (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`) e o **Redis** (`RATE_LIMIT_REDIS_URL`) estiverem provisionados.
  - Para um ambiente de demonstração/homologação de instância única sem Oracle/Redis externos, é possível utilizar `SPRING_PROFILES_ACTIVE=local` com volume persistente montado em `/app/data`, ciente de que no perfil `local` o Swagger fica ativo e o rate limit é em memória.

---

## 5. Pendências remanescentes e próximo passo

1. **Validação em infraestrutura Oracle e Redis reais (Passo 10 / Passo 16):** executar as migrations `classpath:db/migration/oracle` (`V1` e `V2`), validar `ddl-auto=validate` e rodar os testes de concorrência e lock pessimista contra uma instância Oracle descartável e um Redis real.
2. **Passo 16 — Revisão final de portfólio e CI/CD:** revisar artefatos finais de entrega e pipeline após os testes em banco de produção.
