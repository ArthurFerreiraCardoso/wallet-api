package com.example.wallet_api.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.example.wallet_api.api.security.ApiKeyAuthenticationFilter;

/**
 * Faz todo MockMvc do teste mandar a API key valida, passando pelo filtro real em vez de simular um usuario.
 * A chave e gerada a cada execucao. Os testes de seguranca montam um MockMvc sem este padrao.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiKeyTestSupport {

    public static final String API_KEY = "k" + UUID.randomUUID().toString().replace("-", "");

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("wallet.security.api-key", () -> API_KEY);
    }

    @Bean
    MockMvcBuilderCustomizer sendApiKeyByDefault() {
        return builder -> builder.defaultRequest(get("/").header(ApiKeyAuthenticationFilter.HEADER, API_KEY));
    }
}
