package com.example.wallet_api.support;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.wallet_api.api.security.SecurityConfig;

/**
 * Base dos testes de camada web (@WebMvcTest): a cadeia de seguranca real da aplicacao, com a API key de teste
 * enviada por padrao nas requisicoes.
 */
@Import({ SecurityConfig.class, ApiKeyTestSupport.class })
public abstract class WebLayerTest {

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        ApiKeyTestSupport.register(registry);
    }
}
