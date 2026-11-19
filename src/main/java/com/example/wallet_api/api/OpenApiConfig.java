package com.example.wallet_api.api;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.wallet_api.api.security.ApiKeyAuthenticationFilter;
import com.example.wallet_api.api.web.TraceIdFilter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Documento OpenAPI em /v3/api-docs e Swagger UI em /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "apiKey";

    @Bean
    OpenAPI walletOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet API")
                        .version("1.0")
                        .description("""
                                Carteira digital em BRL. As regras de consistencia (saldo nunca negativo, idempotencia, \
                                concorrencia, extrato imutavel e auditoria) sao resolvidas no Oracle; esta API e a camada \
                                HTTP sobre elas. Erros seguem RFC 9457 (application/problem+json) com os campos extras \
                                code, traceId e timestamp (schema ApiProblem)."""))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyAuthenticationFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }

    /** X-Trace-Id vale para toda operacao: aceito na requisicao (opcional) e sempre devolvido na resposta. */
    @Bean
    OperationCustomizer traceIdHeader() {
        return (operation, handlerMethod) -> {
            operation.addParametersItem(new HeaderParameter()
                    .name(TraceIdFilter.HEADER)
                    .required(false)
                    .description("Opcional. Reaproveitado se tiver 8 a 64 caracteres entre letras, numeros e -; senao, gerado.")
                    .schema(new StringSchema().pattern("^[A-Za-z0-9-]{8,64}$")));
            operation.getResponses().values().forEach(response -> response.addHeaderObject(TraceIdFilter.HEADER,
                    new Header().description("Identificador da requisicao, o mesmo dos logs e do campo traceId dos erros")
                            .schema(new StringSchema())));
            return operation;
        };
    }
}
