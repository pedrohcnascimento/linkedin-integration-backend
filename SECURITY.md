# Política de Segurança

## Escopo

Este projeto lida com credenciais OAuth de terceiros (LinkedIn) e conteúdo publicado em nome do usuário. As regras abaixo são obrigatórias e revisadas a cada etapa do roteiro (`docs/planejamento-tecnico.md`).

## Regras técnicas obrigatórias

- **Nunca** versionar `client_secret`, access tokens, refresh tokens, `.env` real ou dumps de banco com dados reais
- Senhas locais devem ser armazenadas somente como hashes BCrypt e nunca devem aparecer em respostas ou logs
- Rotas autenticadas usam sessão HTTP; toda requisição que altera estado exige token CSRF
- Cookies de sessão devem ser HttpOnly e, em produção, Secure sob HTTPS
- Access tokens (e refresh tokens, se existirem) devem ser **cifrados em repouso**, com a chave de cifragem fora do banco de dados
- Logs devem mascarar tokens, códigos OAuth e identificadores sensíveis — nenhum log deve conter um token em texto claro
- O fluxo OAuth deve validar `state` de uso único (proteção CSRF) e usar `redirect_uri` exata, sem curingas
- HTTPS obrigatório em produção
- CORS restrito às origens conhecidas; CSRF habilitado para sessões baseadas em cookie ou justificativa documentada se a API usar somente bearer tokens
- Rate limiting nos endpoints de OAuth e de publicação
- Erros nunca devem vazar stack traces ou detalhes internos ao cliente
- Backups do banco devem ser cifrados
- O usuário deve conseguir desconectar sua conta do LinkedIn a qualquer momento, com invalidação dos tokens locais

## Escopo de dados coletados do LinkedIn

Apenas os dados mínimos de identidade necessários para associar a autorização ao usuário local. Nenhuma coleta de dados de terceiros (recrutadores, outros membros, conexões) por scraping ou qualquer meio não documentado pela API oficial.

## Reportando uma vulnerabilidade

Este é um projeto pessoal/portfólio. Para reportar uma vulnerabilidade:

1. Não abra uma issue pública com detalhes de exploração
2. Descreva o problema, o impacto potencial e passos de reprodução em um canal privado (ex.: e-mail do mantenedor, a ser definido em `README.md`)
3. Aguarde confirmação antes de divulgar publicamente

## Fora do escopo de segurança deste projeto

- Vulnerabilidades na plataforma do LinkedIn em si — reportar diretamente ao LinkedIn
- Uso indevido por terceiros que rodem o próprio fork fora das práticas aqui descritas
