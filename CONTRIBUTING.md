# Contribuindo

## Antes de tudo

Leia [`docs/status-atual.md`](docs/status-atual.md), [`docs/linkedin-capability-matrix.md`](docs/linkedin-capability-matrix.md) e [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md). Eles definem o estado vigente, o escopo autorizado da API oficial do LinkedIn, o que está deliberadamente fora de escopo e a arquitetura em camadas orientada a DDD. Nenhuma contribuição — humana ou de agente de IA — deve contradizer esses limites sem decisão explícita registrada.

## Branch e versionamento

- Branch principal: `main` (integração contínua também em `develop`)
- Branches de trabalho: `feature/<nome-curto>`, `fix/<nome-curto>`, `docs/<nome-curto>`
- Commits seguem [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`, `ci:`, `security:`

## Ambiente

- Java 21 (LTS)
- Maven 3.9+ (do sistema ou integrado à IDE) e Docker opcional (para `docker compose` e `ProdProfileOracleRedisIntegrationTest` com Testcontainers)
- Nenhuma dependência de conta real do LinkedIn é necessária para rodar a suíte automatizada (`MockRestServiceServer` e mocks isolam o provedor externo)

## Regras obrigatórias para qualquer agente de IA (ou colaborador automatizado)

Antes de modificar o projeto:

1. Confirmar o estado atual em [`docs/status-atual.md`](docs/status-atual.md) e o roteiro em [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md)
2. Inspecionar a estrutura existente, o branch ativo, o build e os testes
3. Ler o `README.md`, as configurações (`application*.yml`) e as migrations Flyway (`sqlite` e `oracle`) já existentes
4. Procurar implementações duplicadas antes de criar novos arquivos
5. Propor alterações pequenas e coerentes com a arquitetura em camadas DDD (`client`, `config`, `domain`, `dto`, `exception`, `repository`, `resource`, `security`, `service`)
6. **Nunca** adicionar tokens, senhas, cookies, arquivos `.env` reais ou dados pessoais ao Git
7. **Não** implementar endpoint, scope ou produto do LinkedIn que não esteja confirmado na documentação oficial e registrado em [`docs/linkedin-capability-matrix.md`](docs/linkedin-capability-matrix.md)
8. Escrever ou atualizar testes (incluindo paridade OpenAPI e paridade SQLite/Oracle quando aplicável) junto com qualquer alteração de comportamento
9. Executar o build e a suíte de testes antes de considerar a entrega concluída
10. Registrar um relatório em `docs/progress/STEP-XX.md` quando concluir uma etapa formal do roteiro

**Parar e pedir decisão humana quando:**
- Um teste falhar e a causa não for óbvia
- Uma migration for destrutiva
- Uma permissão ou comportamento do LinkedIn estiver indefinido ou divergir da matriz de capacidades
- Uma alteração puder enfraquecer o modelo de segurança (autenticação, autorização, cifragem, rate limit, CORS/CSRF)

## Checklist de qualidade

Uma entrega só é considerada concluída quando:

- [ ] O objetivo está demonstrado por teste automatizado ou evidência reproduzível
- [ ] O código compila com `--release 21` e a suíte de testes passa
- [ ] O comportamento e o contrato OpenAPI estão documentados e alinhados
- [ ] Não existem segredos ou dados reais no repositório (compatível com `.gitleaks.toml`)
- [ ] Erros e limites conhecidos estão registrados em `docs/status-atual.md` / `README.md`
- [ ] Não há acoplamento indevido de contratos externos do LinkedIn fora do pacote `client`
- [ ] Novas migrations Flyway existem em paridade para `sqlite` e `oracle` e foram testadas em banco limpo e em upgrade
