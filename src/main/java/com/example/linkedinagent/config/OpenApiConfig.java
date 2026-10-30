package com.example.linkedinagent.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenApiCustomizer logoutEndpointOpenApiCustomizer() {
        return openApi -> openApi.path("/api/v1/auth/logout", new PathItem().post(new Operation()
                .addTagsItem("Authentication")
                .operationId("logout")
                .summary("Encerrar sessão local")
                .description("Invalida a sessão HTTP atual e limpa o contexto de autenticação. "
                        + "Exige token CSRF válido no header indicado por GET /api/v1/auth/csrf "
                        + "(enviado automaticamente pelo Swagger no perfil local).")
                .responses(new ApiResponses()
                        .addApiResponse("204", new ApiResponse()
                                .description("Sessão local encerrada (sem corpo)."))
                        .addApiResponse("403", new ApiResponse()
                                .description("Token CSRF ausente ou inválido; execute GET /api/v1/auth/csrf e tente novamente (sem corpo).")))));
    }
}
