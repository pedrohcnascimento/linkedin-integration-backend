# Migrations do Flyway

Este diretório recebe as migrations versionadas do schema SQLite a partir do **Passo 6**
(`docs/planejamento-tecnico.md`, roteiro operacional). Por enquanto está vazio de propósito —
o arquivo existe só para o Flyway conseguir resolver `classpath:db/migration` sem lançar erro
de location ausente.

Convenção de nomenclatura a seguir a partir do Passo 6: `V1__create_app_user.sql`,
`V2__create_linkedin_authorization.sql`, etc.
