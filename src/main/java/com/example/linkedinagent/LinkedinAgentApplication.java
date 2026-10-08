package com.example.linkedinagent;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada da aplicação.
 *
 * <p>Esta classe deve permanecer mínima: nenhuma lógica de negócio, de integração
 * com o LinkedIn ou de persistência deve residir aqui. Ver {@code docs/planejamento-tecnico.md},
 * seção 4, para os limites de cada camada da arquitetura hexagonal.</p>
 */
@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "LinkedIn Integration Backend API",
        version = "v1",
        description = """
                ## Como testar a conexão com o LinkedIn (perfil local)

                O login local e o login do LinkedIn são etapas diferentes. Siga esta ordem:

                1. Execute `POST /api/v1/auth/register` para criar uma conta local.
                2. Execute `POST /api/v1/auth/login` com a conta local.
                3. Confirme a sessão com `GET /api/v1/users/me` (deve retornar `200`).
                4. Execute `GET /api/v1/linkedin/oauth/start`.
                5. Copie a URL `Location` retornada no redirecionamento `302` e abra-a diretamente na barra de endereço do navegador. Não use o botão `Execute` do Swagger para seguir o redirecionamento externo.
                6. Faça login e autorize o aplicativo na página do LinkedIn.
                7. Aguarde o retorno para `/api/v1/linkedin/oauth/callback?code=...&state=...`.
                8. Execute `GET /api/v1/linkedin/connection` para confirmar `connected: true`.

                A URL de callback local deve estar cadastrada exatamente como `http://localhost:8080/api/v1/linkedin/oauth/callback` no LinkedIn Developer Portal. O aplicativo deve ter os produtos **Sign In with LinkedIn using OpenID Connect** e **Share on LinkedIn** habilitados para os scopes `openid profile w_member_social`.

                O Swagger local preserva a sessão e envia o CSRF automaticamente. O erro `401` indica que é necessário fazer login local; `Failed to fetch` ao executar `oauth/start` é esperado porque o endpoint redireciona para outro domínio.

                ## Como testar drafts pelo Swagger local

                1. Execute `GET /api/v1/auth/csrf` antes do primeiro `POST` ou `PATCH`. O token fica no cookie `XSRF-TOKEN`.
                2. Execute `POST /api/v1/auth/register` e depois `POST /api/v1/auth/login` usando a mesma sessão do navegador. O login renova o cookie CSRF automaticamente.
                3. Execute `GET /api/v1/drafts` para listar somente os drafts do usuário autenticado. Use `page` começando em `0` e `size` entre `1` e `50`.
                4. Execute `POST /api/v1/drafts` para criar um draft textual. O Swagger local envia `X-XSRF-TOKEN` automaticamente a partir do cookie.
                5. Execute `PATCH /api/v1/drafts/{id}` para editar um draft que ainda esteja em `DRAFT`. Após a aprovação, a edição é bloqueada.
                6. Se uma operação que altera dados retornar `403`, execute novamente `GET /api/v1/auth/csrf` e tente a operação outra vez. Não copie o token para o corpo JSON; ele deve ser enviado no header CSRF.
                """))
public class LinkedinAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkedinAgentApplication.class, args);
    }
}
