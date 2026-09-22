# linkedin-integration-backend

Backend próprio, em Java/Spring Boot, para integração **autorizada** com o LinkedIn via API oficial (OAuth 2.0 + produto **Share on LinkedIn**).

> ⚠️ Este projeto **não** faz scraping, automação de navegador ou qualquer ação fora dos endpoints oficiais documentados pelo LinkedIn. Ver [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md) para o racional completo e os limites deliberados de escopo.

## Status atual

🚧 **Passo 1 do roteiro operacional concluído** — estrutura documental do repositório. Nenhum código de domínio foi escrito ainda.

Acompanhe o progresso em [`docs/progress/`](docs/progress) (um arquivo `STEP-XX.md` por etapa concluída) e o roteiro completo em [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md#15-roteiro-operacional-cronológico-passo-a-passo-executável).

## Stack técnica

| Item | Escolha |
|---|---|
| Linguagem | Java 21 (LTS) |
| Build | Maven |
| Framework | Spring Boot (Web, Security quando necessário, Validation, Actuator) |
| Persistência | SQLite (dev) → PostgreSQL (futuro), via JPA/Hibernate + migrations versionadas |
| Autenticação externa | OAuth 2.0 com o LinkedIn |
| Documentação de API | springdoc-openapi |
| Empacotamento | Docker |

## O que este projeto faz (v1)

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

## Como rodar (quando o código existir)

```bash
# build
./mvnw clean install

# subir localmente (perfil "local")
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# rodar os testes
./mvnw test
```

Variáveis de ambiente necessárias (ver `.env.example` quando criado no Passo 5):

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

Nenhuma dessas variáveis deve conter valores reais no repositório.

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
