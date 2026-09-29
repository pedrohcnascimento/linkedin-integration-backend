# STEP-04b — Correção pós-build: YAML com valor terminado em `:`

> **Registro histórico da correção.** Este relatório descreve a causa e a correção no momento em que ocorreram; consulte [`docs/status-atual.md`](../status-atual.md) para o estado atual do projeto.

**Contexto:** você rodou `mvn clean test` localmente (obrigado!). Resultado:

- ✅ Compilação: 18 arquivos, sem erros — `pom.xml` resolveu tudo de primeira, incluindo o `hibernate-community-dialects` que eu tinha marcado como maior risco (era gerenciado pelo BOM do Spring Boot 4.1 mesmo, sem precisar de versão explícita)
- ✅ `sqlite-jdbc:3.53.2.0`, `springdoc-openapi:3.0.3`, starters modulares (`webmvc`, `flyway`) — todos resolvidos sem ajuste
- ❌ Teste de contexto falhou: erro de parsing YAML em `application-test.yml`

## Causa raiz

```
url: jdbc:sqlite::memory:
```

Essa string termina em `:` (dois-pontos). Em YAML, dois-pontos no fim de uma linha (ou seguido de espaço) é interpretado como início de um mapeamento — não como parte de um valor de texto. O SnakeYAML (usado pelo Spring Boot) rejeitou a linha.

## Correção

Coloquei a URL entre aspas em ambos os arquivos de configuração (`application.yml` e `application-test.yml`), o que é a prática correta sempre que um valor YAML contém `:`:

```yaml
url: "jdbc:sqlite::memory:"
```

Validei a sintaxe dos dois arquivos com um parser YAML real (PyYAML) neste ambiente — algo que eu não conseguia fazer com o `pom.xml`/Maven. Ambos os arquivos agora carregam sem erro.

## Próxima ação

Rode `mvn clean test` de novo. Essa era a única falha reportada — não deve aparecer mais nenhum outro erro relacionado a isso.
