package com.example.wallet_api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base para testes de integracao com contexto Spring: a aplicacao conecta como WALLET_APP, igual em producao.
 * Configuracao unica para que o contexto seja reaproveitado entre as classes de teste.
 */
@SpringBootTest
public abstract class OracleIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OracleTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", DbUser.APP::username);
        registry.add("spring.datasource.password", DbUser.APP::password);
    }
}
