# Revalidação oficial do Passo 9

Data da verificação: 08/10/2026.

Fontes oficiais consultadas:

- https://learn.microsoft.com/en-us/linkedin/marketing/community-management/shares/posts-api?view=li-lms-2026-09
- https://learn.microsoft.com/en-us/linkedin/marketing/versioning?view=li-lms-2026-09
- https://linkedin.github.io/rest.li/spec/protocol

Confirmações aplicadas:

- Criação de post de membro usa `POST https://api.linkedin.com/rest/posts`.
- Permissão para postar em nome de membro autenticado: `w_member_social`.
- Headers obrigatórios: `Authorization: Bearer ...`, `Content-Type: application/json`, `X-Restli-Protocol-Version: 2.0.0` e `Linkedin-Version: YYYYMM`.
- A versão mais recente indicada pela documentação consultada é `202609`; versões são mensais e têm suporte mínimo de um ano. A aplicação deve manter a versão configurável.
- Post textual orgânico usa `author`, `commentary`, `visibility`, `distribution`, `lifecycleState` e `isReshareDisabledByAuthor`.
- Criação bem-sucedida retorna `201 Created`; o identificador fica no header `x-restli-id`/`X-RestLi-Id`, não depende de corpo JSON.
- Erros relevantes da Posts API incluem 400, 401, 403, 409, 422, 429, 500 e 503; o adaptador deve convertê-los em erros internos controlados sem devolver corpo bruto.
- A documentação alerta que a versão 202510 será encerrada em 15/10/2026; não usar essa versão como default.

Escopo inicial da implementação:

- Adaptador para publicação textual em perfil de membro.
- Sem imagens, vídeos, documentos, carrosséis, páginas de organização, leitura de posts ou publicação automática.
- O caso de uso de rascunho/aprovação/idempotência pertence ao Passo 10; o Passo 9 fornece somente a porta e o adaptador externo necessários para publicação.
