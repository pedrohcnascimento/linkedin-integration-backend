# linkedin-integration-backend

Backend próprio, em Java/Spring Boot, para integração **autorizada** com o LinkedIn via API oficial (OAuth 2.0 + produto **Share on LinkedIn**).

> ⚠️ Este projeto **não** faz scraping, automação de navegador ou qualquer ação fora dos endpoints oficiais documentados pelo LinkedIn. Ver [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md) para o racional completo e os limites deliberados de escopo.

## Status atual

🚧 **Passo 8 implementado e validado por testes automatizados** — conexão/desconexão LinkedIn via OAuth, tokens cifrados e isolamento por usuário. App real, smoke test, rate limiting OAuth e validação no JDK 21 seguem pendentes antes de produção; publicação e demais recursos de negócio estão fora do passo atual.

Acompanhe o progresso em [`docs/progress/`](docs/progress) (um arquivo `STEP-XX.md` por etapa concluída) e o roteiro completo em [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md#15-roteiro-operacional-cronológico-passo-a-passo-executável).

## Stack técnica

| Item | Escolha |
|---|---|
| Linguagem | Java 21 (LTS) |
| Build | Maven |
| Framework | Spring Boot **4.1.0** (Webmvc, Security, Validation, Actuator) |
| Persistência | SQLite (dev) → PostgreSQL (futuro), via JPA/Hibernate 7 + Flyway |
| Autenticação externa | OAuth 2.0 + OpenID Connect com o LinkedIn |
| Publicação | LinkedIn **Posts API** (`/rest/posts`) — ver `docs/linkedin-capability-matrix.md` |
| Documentação de API | springdoc-openapi 3.x |
| Empacotamento | Docker |

> Nota de versão: o projeto usa Spring Boot 4.x porque a linha 3.5.x encerrou o suporte OSS em 25/06/2026. O Boot 4 trouxe starters modulares (`spring-boot-starter-webmvc` no lugar de `spring-boot-starter-web`, por exemplo) — se for comparar com tutoriais mais antigos, tenha isso em mente.

## O que este projeto faz (v1)

- Permite que vários usuários criem contas locais e autentiquem-se em sessões independentes
- Conecta a conta do LinkedIn do usuário via OAuth 2.0
- Cria e revisa rascunhos de publicação
- Publica texto e URLs no LinkedIn em nome do usuário, mediante aprovação explícita (produto **Share on LinkedIn**, escopo `w_member_social`)
- Mantém histórico local de publicações, com idempotência
- Permite registrar e organizar oportunidades de vaga **manualmente ou de fontes autorizadas** — sem buscar, salvar ou se candidatar automaticamente dentro do LinkedIn

## O que este projeto deliberadamente não faz

- Não automatiza login, navegação ou cliques no LinkedIn
- Não usa cookies de sessão do LinkedIn
- Não faz scraping de perfis, vagas ou resultados de busca
- Não envia convites, mensagens ou candidaturas automaticamente
- Não usa Easy Apply de forma automatizada

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
```

Nenhuma dessas variáveis deve conter valores reais no repositório.

### Autenticação local

- `GET /api/v1/auth/csrf` retorna o header e o token CSRF necessários às requisições que alteram estado.
- `POST /api/v1/auth/register` cria uma conta com `email`, `displayName` e senha (mínimo de 12 caracteres).
- `POST /api/v1/auth/login` recebe `email` e `password`, cria uma sessão HTTP e retorna o usuário autenticado.
- `GET /api/v1/users/me` retorna a conta associada à sessão atual.
- `POST /api/v1/auth/logout` encerra a sessão.

O cliente deve guardar o cookie de sessão com segurança e enviar o token CSRF no header indicado pela rota `/csrf`; tokens de senha nunca são devolvidos pela API. Em produção, a aplicação deve ser publicada exclusivamente por HTTPS.

### Conexão com o LinkedIn

- `GET /api/v1/linkedin/oauth/start` inicia a autorização e redireciona ao LinkedIn; exige sessão local e configuração OAuth/chave de cifragem válidas.
- `GET /api/v1/linkedin/oauth/callback` recebe o retorno público do LinkedIn, protegido por `state` de uso único, e devolve JSON sem tokens.
- `GET /api/v1/linkedin/connection` consulta o estado sanitizado da conexão do usuário autenticado.
- `DELETE /api/v1/linkedin/connection` desconecta localmente; exige sessão e token CSRF.

O callback está configurado como `http://localhost:8080/api/v1/linkedin/oauth/callback` apenas para desenvolvimento local. Em produção, configure a URI HTTPS exata também no Developer Portal. Os scopes padrão são `openid profile w_member_social`; os scopes efetivamente concedidos dependem dos produtos habilitados no app. Nenhum teste automatizado chama o LinkedIn real. A desconexão remove credenciais locais, mas não afirma revogação remota.

## Limitações conhecidas (v1)

- Sem refresh token presumido — depende do que o LinkedIn efetivamente conceder para o app registrado
- Sem agendamento nativo no LinkedIn — se existir agendamento, é responsabilidade da aplicação
- Vagas são só um registro local; não há integração automatizada de busca/candidatura
- SQLite é adequado para dev/portfólio; produção em escala exigiria migração para PostgreSQL (já prevista na arquitetura)

## Contribuindo

Veja [`CONTRIBUTING.md`](CONTRIBUTING.md) — inclui as regras que qualquer agente de IA (ou pessoa) deve seguir antes de alterar este repositório.

## Segurança

Veja [`SECURITY.md`](SECURITY.md).

## Licença

MIT — veja [`LICENSE`](LICENSE).
