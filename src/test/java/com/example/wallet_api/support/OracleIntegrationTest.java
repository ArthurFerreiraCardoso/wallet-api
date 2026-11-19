package com.example.wallet_api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base para testes de integracao com contexto Spring: a aplicacao conecta como WALLET_APP, igual em producao.
 * Configuracao unica para que todas as classes reaproveitem o mesmo contexto (e o mesmo pool de conexoes).
 * O pool e pequeno porque o Oracle Free tem poucas sessoes e outros testes abrem conexoes proprias.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class OracleIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OracleTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", DbUser.APP::username);
        registry.add("spring.datasource.password", DbUser.APP::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }
}
