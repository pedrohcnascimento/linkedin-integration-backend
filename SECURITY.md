# Política de Segurança

## Escopo

Este projeto lida com credenciais OAuth 2.0 de terceiros (LinkedIn) e publicação textual autorizada em nome do membro autenticado. As regras abaixo são obrigatórias em todo ciclo de evolução do repositório (ver [`docs/PLANO_AUDITORIA_SEGURANCA.md`](docs/PLANO_AUDITORIA_SEGURANCA.md) e [`docs/status-atual.md`](docs/status-atual.md)).

## Controles técnicos implementados e obrigatórios

- **Proteção de segredos:** nunca versionar `client_secret`, access tokens, refresh tokens, chaves criptográficas, `.env` real ou bancos SQLite locais (`data/*.db`). O pipeline CI executa Gitleaks sobre o histórico completo.
- **Autenticação e sessão local:** senhas armazenadas exclusivamente com BCrypt (limite validado de 72 bytes UTF-8), rotação de `JSESSIONID` no login contra *session fixation*, timeout de inatividade (`30m`) e timeout absoluto de sessão (`8h`).
- **Cookies e CSRF:** cookies `HttpOnly` e `SameSite=Lax` (`Secure=true` e HSTS `max-age=31536000 ; includeSubDomains` no perfil `prod`); todas as rotas que alteram estado exigem token CSRF válido.
- **Perfil padrão fail-safe (`prod`):** sem `SPRING_PROFILES_ACTIVE` explícito, a aplicação inicia em `prod` (desativando `/swagger-ui/**` e `/api-docs` e exigindo segredos de alta entropia, Oracle e Redis).
- **Cifragem de tokens em repouso:** access tokens são cifrados com **AES-256-GCM** (`AesGcmTokenCipher`) usando chave de 32 bytes fora do banco, identificador de chave (`v1.<keyId>.<payload>`) para rotação sem downtime e `appUserId` como AAD (*Additional Authenticated Data*). Refresh tokens não são persistidos nesta versão.
- **OAuth 2.0 seguro:** `state` de 256 bits persistido apenas por hash SHA-256, validade de 10 minutos, consumo atômico de uso único e vínculo estrito à sessão local autenticada que iniciou o fluxo; `redirect_uri` exata sem curingas; `Referrer-Policy: no-referrer`.
- **Rate limiting fail-closed:** limites dedicados para CSRF, cadastro, login (com orçamento por `account-ip` apenas em falhas para evitar lockout DoS), OAuth, leitura (`api-read`), escrita/agendamento de drafts (`draft-write`) e publicação (`publication-write`). Em `prod`, usa scripts Lua atômicos no Redis com identificadores pseudonimizados via `HMAC-SHA256` e falha fechada (`503` + `Retry-After: 5`) se o Redis ficar indisponível. Cabeçalhos `X-Forwarded-For` só são lidos quando o endereço remoto pertence a `TRUSTED_PROXY_IPS`.
- **Sanitização e LGPD:** respostas de erro nunca expõem stack traces ou detalhes internos (`GlobalExceptionHandler` + limite de `64 KB` por requisição); `AuditEvent` bloqueia marcadores de segredos; `DELETE /api/v1/users/me` exclui conta, tokens, drafts e publicações (`ON DELETE CASCADE`) e anonimiza eventos de auditoria (`ON DELETE SET NULL`), com retenção configurável (`AUDIT_EVENT_RETENTION`, padrão `P90D`).

## Escopo de dados coletados do LinkedIn

Apenas os dados mínimos de identidade OpenID Connect (`sub`) necessários para associar a autorização ao usuário local e publicar via **Posts API** (`w_member_social`). Nenhuma coleta de dados de terceiros por scraping ou automação de navegador.

## Reportando uma vulnerabilidade

Para reportar uma vulnerabilidade de segurança neste repositório:

1. **Não** abra uma issue pública com detalhes de exploração ou credenciais.
2. Utilize o recurso privado **Security → Report a vulnerability** (*GitHub Private Vulnerability Reporting* / Security Advisories) em [`github.com/pedrohcnascimento/linkedin-integration-backend/security/advisories/new`](https://github.com/pedrohcnascimento/linkedin-integration-backend/security/advisories/new), descrevendo o componente afetado, o impacto potencial e os passos de reprodução.
3. Aguarde confirmação e correção antes de qualquer divulgação pública.

## Fora do escopo de segurança deste projeto

- Vulnerabilidades na plataforma do LinkedIn em si — reportar diretamente ao LinkedIn.
- Uso indevido por terceiros que operem forks modificados fora das práticas e limites documentados neste repositório.
