# STEP-01 — Criar o repositório e o contrato de trabalho

**Objetivo:** estabelecer um repositório reproduzível e regras para futuras contribuições humanas e automatizadas, sem gerar código de domínio.

## Decisões tomadas (confirmadas com o proprietário)

- Ferramenta de build: **Maven**
- Versão do Java: **21 (LTS)**
- Pacote base: `com.example.linkedinagent` (conforme sugerido no planejamento)
- Licença: **MIT** (assumida para projeto de portfólio — **pendente de confirmação do nome do titular** em `LICENSE`)
- Branch principal: `main`

## Alterações

- `git init` com branch `main`
- `README.md` — visão geral, status, stack, o que o projeto faz/não faz, como rodar, limitações conhecidas
- `.gitignore` — Java/Maven/IDE/OS + proteção explícita contra segredos, `.env`, chaves e arquivos SQLite
- `LICENSE` — MIT
- `CONTRIBUTING.md` — convenções de commit/branch + as 10 regras obrigatórias para agentes de IA + checklist de passagem de etapa, extraídas de `docs/planejamento-tecnico.md`
- `SECURITY.md` — política técnica de segurança e processo de report de vulnerabilidade
- `docs/planejamento-tecnico.md` — cópia integral e revisada do documento de planejamento original, como fonte de verdade versionada do projeto
- `docs/progress/` — pasta criada para os relatórios de cada etapa

## Comandos executados

```bash
git init -q -b main
```

(Build e testes ainda não se aplicam — não há código-fonte nesta etapa.)

## Testes

Não aplicável ao Passo 1 (etapa puramente documental, conforme instrução do próprio planejamento).

## Riscos / pendências

- `LICENSE` tem placeholder `[SEU NOME AQUI]` — precisa do nome/organização real do titular do copyright
- Nenhum código ainda existe; o critério de verificação ("clone limpo explica como executar o projeto") está satisfeito pelo README, mas os comandos (`./mvnw ...`) só funcionarão a partir do Passo 4

## Próximo passo

**Passo 2 — Validar a API e congelar o escopo da v1**: criar `docs/linkedin-capability-matrix.md`, confirmando fontes oficiais para cada chamada planejada (Share on LinkedIn / OAuth 2.0) e registrando explicitamente o que fica de fora da v1.
