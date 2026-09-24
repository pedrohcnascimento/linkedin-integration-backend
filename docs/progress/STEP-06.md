# STEP-06 — Criar schema SQLite e migrations

**Objetivo:** Estabelecer a persistência reproduzível antes da lógica de negócios, definindo o banco de dados via Flyway e o mapeamento das entidades JPA, respeitando as boas práticas para SQLite.

## Alterações

- **Flyway:** Criada a migration inicial `V1__Create_initial_schema.sql` configurando todas as tabelas principais (usuário, autorizações, rascunhos, oportunidades, etc.). Como é SQLite, usamos o tipo flexível `TEXT` para os IDs (que serão UUIDs) e para as datas (armazenadas em formato ISO-8601 UTC).
- **Entidades JPA:** Foram criadas 7 entidades no pacote `com.example.linkedinagent.adapter.out.persistence.entity` representando a estrutura descrita no Planejamento Técnico.
- **Correções do Hibernate:** Adicionamos explicitamente `columnDefinition = "TEXT"` em todas as propriedades do tipo `Instant` e `UUID` para garantir que a validação de DDL do Hibernate passe corretamente no perfil `test` (onde usamos `ddl-auto: validate`).
- **Repositórios:** Criadas as interfaces `JpaRepository` com métodos de busca específicos essenciais, por exemplo `findByStateHash` e `findByEmail`.
- **Validação:** Um teste de integração, `SchemaValidationTest`, foi adicionado para persistir e buscar uma entidade garantindo que não haverá problemas ocultos de casting na transação do SQLite.

## Testes

- O Spring Boot processou os schemas do Flyway em memória sem erros.
- A validação restrita do DDL do Hibernate confirmou compatibilidade dos mapeamentos.
- `mvn clean test` executado com êxito em todo o contexto.

## Próximo passo

- **Passo 7 — Implementar identidade local e autorização da aplicação.** Consiste em criar a porta de entrada (casos de uso) para criação e autenticação básica do usuário "dono" da aplicação antes da integração final com OAuth do LinkedIn.
