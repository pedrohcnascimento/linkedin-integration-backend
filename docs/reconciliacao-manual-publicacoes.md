# Procedimento operacional — Reconciliação manual de publicações (`PUBLISHING` e `OUTCOME_UNKNOWN`)

**Status:** vigente a partir de 30/10/2026  
**Escopo:** rascunhos textuais do fluxo `POST /api/v1/drafts/{id}/publish` nos perfis `local` (SQLite) e `prod` (Oracle)  
**Referências:** [`status-atual.md`](status-atual.md), [`linkedin-capability-matrix.md`](linkedin-capability-matrix.md) e [`progress/STEP-10.md`](progress/STEP-10.md)

---

## 1. Objetivo e racional de segurança

Quando o backend envia um post textual para `POST https://api.linkedin.com/rest/posts`, não existe transação distribuída entre o banco local e a API externa do LinkedIn. Por isso, a aplicação adota uma política **fail-closed contra duplicação**:

1. **Sem retry automático:** falhas de transporte, timeouts ou respostas ambíguas nunca disparam novo `POST /rest/posts` automaticamente.
2. **Sem reconciliação automática via API:** o aplicativo opera com `openid profile w_member_social`. O LinkedIn pausou novas concessões de `r_member_social` (leitura de posts/feed do próprio membro — ver [`linkedin-capability-matrix.md`](linkedin-capability-matrix.md)), portanto o backend não pode consultar o feed do membro por API para descobrir se o post foi criado.
3. **Sem endpoint administrativo nesta versão:** a reconciliação é feita pelo operador diretamente no banco de dados após conferência visual no perfil real do titular no LinkedIn, registrando trilha em `audit_event`.

---

## 2. Como os estados bloqueantes surgem e como bloqueiam novas tentativas

| Estado em `publication.status` | `failure_code` gravado | `content_draft.status` | Como surge no código (`DraftWorkflowService`) |
|---|---|---|---|
| `PUBLISHING` | `NULL` | `APPROVED` | Gravado em transação própria (`PROPAGATION_REQUIRES_NEW`) **antes** da chamada HTTP ao LinkedIn. Indica chamada ainda em curso **ou** interrupção abrupta do processo/JVM/banco antes de gravar a conclusão. |
| `OUTCOME_UNKNOWN` | `LINKEDIN_RESULT_UNKNOWN` | `APPROVED` | Gravado após falha de transporte/timeout (`connectTimeout = 5s`, `readTimeout = 10s`), resposta HTTP `408`, `409` ou `5xx` do LinkedIn, ou resposta `2xx` sem o header `x-restli-id`. A requisição inicial recebeu `502 LINKEDIN_RESULT_UNKNOWN`. |

Enquanto existir qualquer linha em `publication` com `status IN ('PUBLISHING', 'OUTCOME_UNKNOWN')` para `(app_user_id, draft_id)`:

- Repetir a **mesma** `Idempotency-Key` retorna `409 Conflict` com código `PUBLICATION_ALREADY_PROCESSED`.
- Enviar uma **nova** `Idempotency-Key` para o mesmo `draft_id` retorna `409 Conflict` com código `PUBLICATION_OUTCOME_UNKNOWN`.
- Editar o rascunho (`PATCH /api/v1/drafts/{id}`) permanece bloqueado porque `content_draft.status` já está em `APPROVED`.

---

## 3. Regras inegociáveis antes de qualquer escrita manual

1. **Respeite a janela mínima de segurança antes de tocar em `PUBLISHING`:** como `PUBLISHING` também representa uma chamada legítima em execução (timeout de conexão de 5 s + leitura de 10 s + margem de commit), **nunca** reconcilie um registro cujo `created_at` tenha menos de **2 minutos**, e confirme nos logs/processo da aplicação que não há requisição em curso para aquele `draft_id`.
2. **Aguarde propagação no LinkedIn antes de concluir ausência:** aguarde pelo menos **2 a 5 minutos** após `publication.created_at` antes de assumir que o post não foi publicado no perfil.
3. **Nunca execute `DELETE` em `publication`:** apagar a tentativa destrói o histórico e permite que um replay tardio com a mesma `Idempotency-Key` volte a publicar no LinkedIn sem intenção explícita do usuário.
4. **Nunca altere `content_draft.approved_at`:** esse valor compõe o hash SHA-256 de `request_fingerprint` (`appUserId|draftId|approvedAt|idempotencyKey`).
5. **Formato estrito de datas (`InstantStringConverter`):** todas as colunas de instante (`published_at`, `updated_at`, `occurred_at`) são lidas pela aplicação via `Instant.parse(...)`. Grave **exclusivamente** strings UTC no padrão ISO-8601 com sufixo `Z` (ex.: `2026-10-30T12:15:00Z`). Formatos locais de `SYSDATE`/`DATETIME` sem `T` e `Z` quebrarão `GET /api/v1/publications` e `GET /api/v1/drafts` com erro de parse.
6. **Proteção de segredos e privacidade:** `audit_event.metadata_sanitized` não deve conter tokens, senhas, cookies, identificadores de sessão nem dados pessoais desnecessários.
7. **Backup / transação controlada:**
   - **SQLite (`local`):** faça cópia do arquivo `./data/linkedinagent-local.db` (ou use `.backup` no `sqlite3`) antes de alterar dados.
   - **Oracle (`prod`):** execute sempre com `AUTOCOMMIT OFF`, valide que cada `UPDATE` afetou exatamente `1` linha e execute `ROLLBACK` imediato em caso de divergência.

---

## 4. Passo a passo operacional

### Passo 1 — Diagnosticar tentativas pendentes no banco

Liste todas as publicações em estado bloqueante com os dados do rascunho correspondente (compatível com SQLite e Oracle):

```sql
SELECT p.id               AS publication_id,
       p.app_user_id      AS app_user_id,
       p.draft_id         AS draft_id,
       p.status           AS publication_status,
       p.failure_code     AS failure_code,
       p.external_post_id AS external_post_id,
       p.created_at       AS attempt_created_at,
       d.status           AS draft_status,
       d.approved_at      AS draft_approved_at,
       d.title            AS draft_title,
       d.text             AS draft_text
  FROM publication p
  LEFT JOIN content_draft d ON d.id = p.draft_id
 WHERE p.status IN ('PUBLISHING', 'OUTCOME_UNKNOWN')
 ORDER BY p.created_at ASC;
```

Em seguida, inspecione **todo** o histórico do `draft_id` afetado para garantir que você conhece todas as tentativas associadas a ele:

```sql
SELECT id, status, failure_code, external_post_id, published_at, created_at
  FROM publication
 WHERE draft_id = '<DRAFT_UUID>'
 ORDER BY created_at DESC, id DESC;
```

Anote:
- `<PUBLICATION_UUID>`: `publication.id` da tentativa pendente
- `<APP_USER_UUID>`: `publication.app_user_id`
- `<DRAFT_UUID>`: `publication.draft_id`
- `<PREVIOUS_STATUS>`: valor atual (`PUBLISHING` ou `OUTCOME_UNKNOWN`)

---

### Passo 2 — Verificar no perfil real do LinkedIn

1. Junto ao titular da conta autenticada (ou na própria conta, em ambiente individual), abra o perfil no LinkedIn e acesse **Atividade → Publicações** (`/in/<perfil>/recent-activity/all/`).
2. Compare o texto exato (`draft_text`) e a janela de horário próxima a `attempt_created_at`.
3. Classifique o resultado:
   - **O post apareceu no perfil:** siga para o **Passo 3A**.
     - Opcionalmente, clique em **"Copiar link da publicação"** no LinkedIn. Se a URL expuser claramente o URN da publicação (ex.: `urn:li:share:...`, `urn:li:ugcPost:...` ou `urn:li:activity:...`, com até 255 caracteres), utilize-o em `external_post_id`. **Nunca invente um URN sintético**; caso não seja possível confirmar o identificador com certeza, mantenha `external_post_id` como `NULL`.
   - **O post não apareceu no perfil** (após aguardar pelo menos 2 a 5 minutos desde `attempt_created_at` e confirmar que não há requisição ativa): siga para o **Passo 3B**.

---

### Passo 3A — Cenário A: o post FOI publicado no LinkedIn (marcar como `PUBLISHED`)

Atualize `publication` (limpando também `failure_code`), marque `content_draft` como `PUBLISHED` (preservando `approved_at`) e registre o evento em `audit_event` **na mesma transação**.

Gere antes um UUID v4 novo para `<AUDIT_UUID>` e substitua os placeholders abaixo (`<EXTERNAL_POST_ID_OR_NULL>` deve ser `'urn:li:...'` ou `NULL`).

#### SQLite (`local`)

```sql
BEGIN IMMEDIATE;

UPDATE publication
   SET status = 'PUBLISHED',
       failure_code = NULL,
       external_post_id = <EXTERNAL_POST_ID_OR_NULL>,
       published_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
 WHERE id = '<PUBLICATION_UUID>'
   AND app_user_id = '<APP_USER_UUID>'
   AND status IN ('PUBLISHING', 'OUTCOME_UNKNOWN');

UPDATE content_draft
   SET status = 'PUBLISHED',
       updated_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now')
 WHERE id = '<DRAFT_UUID>'
   AND app_user_id = '<APP_USER_UUID>'
   AND status = 'APPROVED';

INSERT INTO audit_event (
    id, app_user_id, event_type, resource_type, resource_id,
    outcome, occurred_at, metadata_sanitized
) VALUES (
    '<AUDIT_UUID>',
    '<APP_USER_UUID>',
    'PUBLICATION_MANUAL_RECONCILIATION',
    'PUBLICATION',
    '<PUBLICATION_UUID>',
    'MARKED_PUBLISHED',
    strftime('%Y-%m-%dT%H:%M:%SZ', 'now'),
    '{"draftId":"<DRAFT_UUID>","previousStatus":"<PREVIOUS_STATUS>","reconciledStatus":"PUBLISHED","verification":"linkedin_profile_activity"}'
);

-- Confira changes() / leitura antes de confirmar; em caso de divergência, execute ROLLBACK.
COMMIT;
```

#### Oracle (`prod`)

```sql
-- Execute com AUTOCOMMIT desativado na sessão.
UPDATE publication
   SET status = 'PUBLISHED',
       failure_code = NULL,
       external_post_id = <EXTERNAL_POST_ID_OR_NULL>,
       published_at = TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
 WHERE id = '<PUBLICATION_UUID>'
   AND app_user_id = '<APP_USER_UUID>'
   AND status IN ('PUBLISHING', 'OUTCOME_UNKNOWN');
-- Confirme: 1 linha atualizada. Se diferente de 1, execute ROLLBACK.

UPDATE content_draft
   SET status = 'PUBLISHED',
       updated_at = TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
 WHERE id = '<DRAFT_UUID>'
   AND app_user_id = '<APP_USER_UUID>'
   AND status = 'APPROVED';
-- Confirme: 1 linha atualizada. Se diferente de 1, execute ROLLBACK.

INSERT INTO audit_event (
    id, app_user_id, event_type, resource_type, resource_id,
    outcome, occurred_at, metadata_sanitized
) VALUES (
    '<AUDIT_UUID>',
    '<APP_USER_UUID>',
    'PUBLICATION_MANUAL_RECONCILIATION',
    'PUBLICATION',
    '<PUBLICATION_UUID>',
    'MARKED_PUBLISHED',
    TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    '{"draftId":"<DRAFT_UUID>","previousStatus":"<PREVIOUS_STATUS>","reconciledStatus":"PUBLISHED","verification":"linkedin_profile_activity"}'
);

COMMIT;
```

**Efeito após o `COMMIT` (Cenário A):**
- Replay com a **mesma** `Idempotency-Key` retorna `201` com o registro reconciliado, sem chamar o LinkedIn novamente.
- Qualquer tentativa com **nova** `Idempotency-Key` para o mesmo `draft_id` retorna `422 DRAFT_NOT_APPROVED` (porque o rascunho já está `PUBLISHED`), impedindo duplicação.

---

### Passo 3B — Cenário B: o post NÃO foi publicado no LinkedIn (marcar como `FAILED` e destravar o rascunho)

Encerre a tentativa pendente como `FAILED` com `failure_code = 'RECONCILED_NOT_PUBLISHED'`, mantendo `content_draft.status = 'APPROVED'` e `approved_at` inalterado, e registre o evento em `audit_event` **na mesma transação**.

Gere antes um UUID v4 novo para `<AUDIT_UUID>` e substitua os placeholders abaixo.

#### SQLite (`local`)

```sql
BEGIN IMMEDIATE;

UPDATE publication
   SET status = 'FAILED',
       failure_code = 'RECONCILED_NOT_PUBLISHED',
       external_post_id = NULL,
       published_at = NULL
 WHERE id = '<PUBLICATION_UUID>'
   AND app_user_id = '<APP_USER_UUID>'
   AND status IN ('PUBLISHING', 'OUTCOME_UNKNOWN');

INSERT INTO audit_event (
    id, app_user_id, event_type, resource_type, resource_id,
    outcome, occurred_at, metadata_sanitized
) VALUES (
    '<AUDIT_UUID>',
    '<APP_USER_UUID>',
    'PUBLICATION_MANUAL_RECONCILIATION',
    'PUBLICATION',
    '<PUBLICATION_UUID>',
    'MARKED_NOT_PUBLISHED',
    strftime('%Y-%m-%dT%H:%M:%SZ', 'now'),
    '{"draftId":"<DRAFT_UUID>","previousStatus":"<PREVIOUS_STATUS>","reconciledStatus":"FAILED","failureCode":"RECONCILED_NOT_PUBLISHED","verification":"linkedin_profile_activity"}'
);

COMMIT;
```

#### Oracle (`prod`)

```sql
-- Execute com AUTOCOMMIT desativado na sessão.
UPDATE publication
   SET status = 'FAILED',
       failure_code = 'RECONCILED_NOT_PUBLISHED',
       external_post_id = NULL,
       published_at = NULL
 WHERE id = '<PUBLICATION_UUID>'
   AND app_user_id = '<APP_USER_UUID>'
   AND status IN ('PUBLISHING', 'OUTCOME_UNKNOWN');
-- Confirme: 1 linha atualizada. Se diferente de 1, execute ROLLBACK.

INSERT INTO audit_event (
    id, app_user_id, event_type, resource_type, resource_id,
    outcome, occurred_at, metadata_sanitized
) VALUES (
    '<AUDIT_UUID>',
    '<APP_USER_UUID>',
    'PUBLICATION_MANUAL_RECONCILIATION',
    'PUBLICATION',
    '<PUBLICATION_UUID>',
    'MARKED_NOT_PUBLISHED',
    TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    '{"draftId":"<DRAFT_UUID>","previousStatus":"<PREVIOUS_STATUS>","reconciledStatus":"FAILED","failureCode":"RECONCILED_NOT_PUBLISHED","verification":"linkedin_profile_activity"}'
);

COMMIT;
```

**Efeito após o `COMMIT` (Cenário B):**
- **Nenhum envio automático ocorre.** Se o usuário desistiu da publicação, nenhuma ação adicional é necessária.
- Qualquer repetição acidental com a **mesma** `Idempotency-Key` antiga continua segura e bloqueada com `409 PUBLICATION_ALREADY_PROCESSED`.
- Se o usuário decidir publicar novamente o rascunho aprovado, ele deve chamar explicitamente `POST /api/v1/drafts/{id}/publish` enviando uma **nova** `Idempotency-Key`.

---

### Passo 4 — Verificação pós-reconciliação

1. Confirme no banco que não restou nenhuma linha bloqueante para o rascunho e que os estados de `publication`, `content_draft` e `audit_event` estão consistentes:

```sql
SELECT COUNT(*) AS unresolved_attempts
  FROM publication
 WHERE draft_id = '<DRAFT_UUID>'
   AND status IN ('PUBLISHING', 'OUTCOME_UNKNOWN');
-- Esperado: 0

SELECT p.id, p.status AS pub_status, p.failure_code, p.external_post_id, p.published_at,
       d.status AS draft_status, d.approved_at, d.updated_at
  FROM publication p
  LEFT JOIN content_draft d ON d.id = p.draft_id
 WHERE p.id = '<PUBLICATION_UUID>';

SELECT id, event_type, resource_id, outcome, occurred_at, metadata_sanitized
  FROM audit_event
 WHERE resource_type = 'PUBLICATION'
   AND resource_id = '<PUBLICATION_UUID>'
 ORDER BY occurred_at DESC;
```

2. Com sessão autenticada do usuário proprietário (quando disponível), consulte `GET /api/v1/publications?page=0&size=20` e confirme que a resposta HTTP `200` reflete o novo estado sem erros de conversão de data/hora.
