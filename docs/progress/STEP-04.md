# STEP-04 — Gerar o esqueleto Spring Boot

**Objetivo:** base compilável e pequena — aplicação que inicia, health check, build configurado, teste de contexto. Sem entidades JPA vazias, sem lógica de integração em controllers.

## Decisão de versão (pesquisa realizada nesta etapa)

- Spring Boot **3.5.x encerrou o suporte OSS em 25/06/2026** (última patch: 3.5.16). Recomendação oficial do time Spring: migrar para 4.0.x/4.1.x.
- Escolhido **Spring Boot 4.1.0** (GA em 10/06/2026), confirmado pelo proprietário do projeto após eu expor o trade-off (mais atual vs. maior risco de detalhe incorreto, já que não consigo compilar neste ambiente).
- Mudança estrutural relevante do Boot 4: **starters modulares**. `spring-boot-starter-web` → `spring-boot-starter-webmvc`; Flyway agora exige `spring-boot-starter-flyway` (não basta a dependência crua); testes usam `spring-boot-starter-webmvc-test`. Fonte: spring.io/blog/2025/10/28/modularizing-spring-boot e o Spring Boot 4.0 Migration Guide (wiki oficial).
- Java: 21 confirmado compatível (Boot 4.1 exige mínimo Java 17).
- springdoc-openapi: usada a série 3.x (`3.0.3`), que é a compatível com Spring Boot 4 / Spring Framework 7.

## ⚠️ Limitação importante deste ambiente — leia antes de reportar bugs

O container onde este código foi escrito **não tem acesso ao Maven Central** (rede restrita a poucos domínios: npm, PyPI, GitHub, etc., mas não `repo.maven.apache.org`/`repo1.maven.org`). Isso significa:

- O código foi escrito com base em pesquisa e conhecimento do Maven/Spring, mas **`mvn clean test` nunca foi executado** neste ambiente.
- A primeira execução real do build precisa ser feita **na sua máquina**, com internet completa.

### Pontos de maior risco para o primeiro build (nesta ordem de probabilidade)

1. **`org.hibernate.orm:hibernate-community-dialects` sem versão explícita no `pom.xml`** — pode ser que o BOM do Spring Boot 4.1 não gerencie essa versão automaticamente. Se o Maven reclamar de "missing version", adicione `<version>${hibernate.version}</version>` (ou a versão exata do Hibernate ORM que o `mvn dependency:tree` mostrar para `hibernate-core`).
2. **Versão exata `4.1.0` do `spring-boot-starter-parent`** — se já existir um patch mais novo (4.1.1, 4.1.2...) quando você compilar, considere usar o mais recente disponível.
3. **Nomes de starters do Spring Boot 4** — confirmados via documentação oficial (`webmvc`, `flyway`), mas é uma área nova; se algo não resolver, o nome do starter mudou é o primeiro lugar a checar na tabela oficial: `github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide`.
4. **`org.xerial:sqlite-jdbc:3.53.2.0`** — versão mais recente confirmada via pesquisa (jun/2026); não há razão técnica para não resolver, mas listo por precaução.

**Ação esperada de você:** rodar `mvn clean test` localmente e me colar qualquer erro de resolução de dependência ou de contexto. Eu corrijo na hora — normalmente é um ajuste de uma linha no `pom.xml`.

## Alterações

- `pom.xml` — Spring Boot 4.1.0, Java 21, dependências mínimas descritas acima
- `src/main/java/com/example/linkedinagent/LinkedinAgentApplication.java` — classe principal, sem lógica
- `package-info.java` em cada um dos 17 pacotes previstos na arquitetura (seção 4.2 do planejamento) — documenta o propósito de cada pacote sem criar classes vazias
- `src/main/resources/application.yml` — datasource SQLite, `ddl-auto: validate` (nunca `update`), Flyway apontando para `classpath:db/migration`, Actuator expondo só `health`, springdoc
- `src/main/resources/db/migration/README.md` — placeholder para o Flyway resolver a location antes das migrations reais (Passo 6)
- `src/test/resources/application-test.yml` — perfil de teste com SQLite em memória, sem credenciais
- `src/test/java/.../LinkedinAgentApplicationTests.java` — teste de contexto (`contextLoads`)
- `data/.gitkeep` + ajuste no `.gitignore` (`data/*` em vez de `data/`) — garante que a pasta usada pelo SQLite local exista após o clone, mesmo com o `.db` ignorado
- `README.md` — atualizado com stack real, nota sobre Spring Boot 4 e instruções de build sem wrapper

## Testes

Não executados neste ambiente (ver limitação acima). Critério de aceite do Passo 4 (`mvn clean test` passar em máquina limpa) **fica pendente de confirmação sua**.

## Riscos / pendências

- Build ainda não verificado (ver seção de risco acima)
- Maven Wrapper (`mvnw`) não incluído — recomendo gerar localmente com `mvn -N wrapper:wrapper` depois que o build estiver validado
- `hibernate-community-dialects` pode precisar de versão explícita

## Próximo passo

Aguardando você rodar o build local e confirmar (ou reportar erro). Depois disso: **Passo 5 — Configurar ambientes e segredos** (profiles `local`/`test`/`prod` mais completos, logs mascarados, CORS).
