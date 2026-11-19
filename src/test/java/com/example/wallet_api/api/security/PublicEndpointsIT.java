package com.example.wallet_api.api.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.example.wallet_api.api.web.TraceIdFilter;
import com.example.wallet_api.support.OracleIntegrationTest;

/**
 * Com a aplicacao inteira: o que fica aberto sem API key e o que nao fica.
 * Usa um MockMvc proprio, sem a chave que os outros testes mandam por padrao.
 */
class PublicEndpointsIT extends OracleIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    TraceIdFilter traceIdFilter;

    private MockMvc anonymous;

    @BeforeEach
    void setUp() {
        anonymous = MockMvcBuilders.webAppContextSetup(context).addFilters(traceIdFilter).apply(springSecurity()).build();
    }

    @Test
    void healthIsOpenAndShowsNoDetails() throws Exception {
        anonymous.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void openApiDocumentIsOpenAndDeclaresTheApiKey() throws Exception {
        anonymous.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"))
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.in").value("header"))
                .andExpect(jsonPath("$.paths['/wallets/{walletId}/transactions'].post").exists());
    }

    /** O contrato publicado descreve o que a API realmente faz (conferido no JSON gerado, nao so nas anotacoes). */
    @Test
    void openApiDocumentDescribesTheRealContract() throws Exception {
        String tx = "$.paths['/wallets/{walletId}/transactions'].post";
        anonymous.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.ApiProblem.properties.code").exists())
                .andExpect(jsonPath("$.components.schemas.ApiProblem.properties.traceId").exists())
                .andExpect(jsonPath("$.components.schemas.ApiProblem.properties.timestamp").exists())
                .andExpect(jsonPath(tx + ".parameters[?(@.name == 'Idempotency-Key')].required").value(true))
                .andExpect(jsonPath(tx + ".parameters[?(@.name == 'Idempotency-Key')].schema.pattern").value("^[A-Za-z0-9_-]{1,64}$"))
                .andExpect(jsonPath(tx + ".parameters[?(@.name == 'X-Trace-Id')]").exists())
                .andExpect(jsonPath(tx + ".responses['409']").exists())
                .andExpect(jsonPath(tx + ".responses['200'].headers['Idempotent-Replayed']").exists())
                .andExpect(jsonPath(tx + ".responses['503'].headers['Retry-After']").exists())
                .andExpect(jsonPath("$.paths['/wallets'].post.responses['201'].headers.Location").exists())
                .andExpect(jsonPath("$.paths['/wallets'].post.responses['401'].headers['WWW-Authenticate']").exists())
                .andExpect(jsonPath("$.components.schemas.CreateWalletRequest.properties.holderName.maxLength").value(120))
                .andExpect(jsonPath("$.components.schemas.RegisterTransactionRequest.properties.type.enum[0]").value("CREDIT"))
                .andExpect(jsonPath("$.components.schemas.RegisterTransactionRequest.required[0]").exists());
    }

    @Test
    void swaggerUiIsOpen() throws Exception {
        anonymous.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger")));
    }

    @Test
    void walletEndpointsRequireTheKey() throws Exception {
        anonymous.perform(get("/wallets/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        anonymous.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
