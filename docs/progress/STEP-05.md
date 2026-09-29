# STEP-05 — Configurar ambientes e segredos

> **Registro histórico da etapa.** Este relatório preserva o estado observado quando foi escrito; não representa o status atual do projeto. Consulte [`docs/status-atual.md`](../status-atual.md) para o estado e as pendências vigentes.

**Objetivo:** separar `local`/`test`/`prod` desde o primeiro commit funcional e mapear de forma segura os segredos (`.env`) para as configurações do Spring.

## Alterações

- `application.yml` reestruturado para ser o arquivo base, definindo o perfil padrão como `local`.
- Adicionado `application-local.yml` com defaults de fallback que permitem inicialização segura em ambiente de desenvolvimento local sem estourar erros de resolução caso o desenvolvedor ainda não tenha preenchido todos os segredos.
- Adicionado `application-prod.yml` que não provê fallbacks para as chaves secretas (falhará rápido se as variáveis de ambiente não estiverem no host, o que é o comportamento esperado de segurança para produção).
- `application-test.yml` atualizado para conter strings fixas "mockadas" de chaves de API, de forma a não depender nem da rede nem de segredos durante a execução de testes automatizados.
- Verificado o arquivo `.env.example`, que já continha exatamente a estrutura documentada em `planejamento-tecnico.md`.
- Adicionada a classe `@ConfigurationProperties` `AppProperties.java` no pacote de config para criar tipagem forte no Java referente às configurações injetadas via YAML/Environment.
- Criada a classe `WebConfig.java` implementando `WebMvcConfigurer` para leitura e aplicação correta da variável `CORS_ALLOWED_ORIGINS`.

## Testes

- O comando `mvn clean test` foi executado e todos os testes (carga de contexto) passaram com sucesso, confirmando que não há problemas de binding das propriedades nem de leitura dos YAMLs.
- Não há valores sensíveis submetidos no versionamento.

## Próximo passo

- **Passo 6 — Criar schema SQLite e migrations.** Consistirá em inicializar as tabelas base do banco local utilizando Flyway.
