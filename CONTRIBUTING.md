# Contribuindo

## Antes de tudo

Leia [`docs/planejamento-tecnico.md`](docs/planejamento-tecnico.md) por completo. Ele define o escopo autorizado, o que está deliberadamente fora de escopo, a arquitetura e o roteiro passo a passo. Nenhuma contribuição — humana ou de agente de IA — deve contradizer esse documento sem uma decisão explícita registrada.

## Branch e versionamento

- Branch principal: `main`
- Branches de trabalho: `feature/<nome-curto>`, `fix/<nome-curto>`, `docs/<nome-curto>`
- Commits seguem [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`, `security:`

## Ambiente

- Java 21 (LTS)
- Maven (`./mvnw` será adicionado no Passo 3 do roteiro)
- Nenhuma dependência de conta real do LinkedIn é necessária para rodar os testes (usar WireMock/MockWebServer)

## Regras obrigatórias para qualquer agente de IA (ou colaborador automatizado)

Antes de modificar o projeto:

1. Confirmar o objetivo da etapa atual no roteiro (`docs/planejamento-tecnico.md`, seção 15)
2. Inspecionar a estrutura existente, o branch ativo, o build e os testes
3. Ler o README, as configurações e as migrations já existentes
4. Procurar implementações duplicadas antes de criar novos arquivos
5. Propor alterações pequenas e coerentes com a arquitetura hexagonal
6. **Nunca** adicionar tokens, senhas, cookies, arquivos `.env` reais ou dados pessoais ao Git
7. **Não** implementar endpoint, scope ou produto do LinkedIn que não esteja confirmado na documentação oficial atual
8. Escrever ou atualizar testes junto com qualquer alteração de comportamento
9. Executar o build e os testes relevantes antes de considerar a etapa concluída
10. Registrar um relatório em `docs/progress/STEP-XX.md` com: objetivo, arquivos alterados, comandos executados, resultado dos testes, riscos/decisões pendentes e o próximo passo

**Parar e pedir decisão humana quando:**
- Um teste falhar e a causa não for óbvia
- Uma migration for destrutiva
- Uma permissão ou comportamento do LinkedIn estiver indefinido ou divergir do planejamento
- Uma alteração puder afetar o modelo de segurança (autenticação, autorização, cifragem, CORS/CSRF)

## Checklist de passagem entre etapas

Uma etapa só é considerada concluída quando:

- [ ] O objetivo está demonstrado por teste ou evidência reproduzível
- [ ] O código compila e os testes relevantes passam
- [ ] O comportamento está documentado
- [ ] Não existem secrets ou dados reais no repositório
- [ ] Erros e limites conhecidos estão registrados
- [ ] Não há dependência indevida entre domínio, LinkedIn ou SQLite
- [ ] As migrations foram testadas em banco limpo (quando aplicável)
- [ ] O próximo colaborador consegue entender o estado atual lendo só README + planejamento + relatório da etapa
