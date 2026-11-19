package com.example.wallet_api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base para testes de integracao com contexto Spring: a aplicacao conecta como WALLET_APP, igual em producao,
 * e as requisicoes do MockMvc levam a API key de teste.
 * Configuracao unica para que todas as classes reaproveitem o mesmo contexto (e o mesmo pool de conexoes);
 * o servidor real em porta aleatoria fica disponivel para os testes que precisam de HTTP de verdade.
 * O pool e pequeno porque o Oracle Free tem poucas sessoes e outros testes abrem conexoes proprias.
 * Subclasses nao devem usar @MockitoBean nem @TestPropertySource: cada variacao cria outro contexto, com outro
 * servidor e outro pool, e pode esgotar as sessoes do Oracle Free (ORA-12516).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(ApiKeyTestSupport.class)
public abstract class OracleIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OracleTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", DbUser.APP::username);
        registry.add("spring.datasource.password", DbUser.APP::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        ApiKeyTestSupport.register(registry);
    }
}
