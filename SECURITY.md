# Política de Segurança

## Escopo

Este projeto lida com credenciais OAuth de terceiros (LinkedIn) e conteúdo publicado em nome do usuário. As regras abaixo são obrigatórias e revisadas a cada etapa do roteiro (`docs/planejamento-tecnico.md`).

## Regras técnicas obrigatórias

- **Nunca** versionar `client_secret`, access tokens, refresh tokens, `.env` real ou dumps de banco com dados reais
- Senhas locais devem ser armazenadas somente como hashes BCrypt e nunca devem aparecer em respostas ou logs
- Rotas autenticadas usam sessão HTTP; toda requisição que altera estado exige token CSRF
- Cookies de sessão devem ser HttpOnly e, em produção, Secure sob HTTPS
- Access tokens (e refresh tokens, se existirem) devem ser **cifrados em repouso com AES-256-GCM**, com uma chave Base64 de 32 bytes fora do banco de dados
- Logs devem mascarar tokens, códigos OAuth e identificadores sensíveis — nenhum log deve conter um token em texto claro
- O fluxo OAuth deve validar `state` de uso único (proteção CSRF) e usar `redirect_uri` exata, sem curingas
- HTTPS obrigatório em produção
- CORS restrito às origens conhecidas; CSRF habilitado para sessões baseadas em cookie ou justificativa documentada se a API usar somente bearer tokens
- Rate limiting em login, cadastro e endpoints OAuth, além dos endpoints de publicação quando implementados. Aplicar limites por IP e, conforme a rota, por conta, sessão ou state; devolver `429` genérico com `Retry-After`. Produção deve usar Redis compartilhado com operações atômicas entre instâncias; falha do Redis deve falhar fechada com `503`, nunca desativar o limite silenciosamente. Endereços encaminhados (`X-Forwarded-For`/`Forwarded`) não são confiáveis por padrão: o proxy de borda deve preservar o IP de origem ou aplicar limite no próprio gateway.
- Os perfis `local` e `test` usam contadores em memória somente para desenvolvimento/testes. No perfil `prod`, `RATE_LIMIT_REDIS_URL` e `RATE_LIMIT_HMAC_KEY` são obrigatórios; gere a chave HMAC em Base64 com pelo menos 32 bytes, use o mesmo valor em todas as instâncias e mantenha-o fora do repositório.
- Erros nunca devem vazar stack traces ou detalhes internos ao cliente
- Backups do banco devem ser cifrados
- O usuário deve conseguir desconectar sua conta do LinkedIn a qualquer momento, removendo os tokens locais; não afirmar revogação remota sem implementá-la e confirmá-la na API oficial

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
