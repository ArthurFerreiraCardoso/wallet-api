package com.example.wallet_api.api.security;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.wallet_api.api.WalletController;
import com.example.wallet_api.application.WalletCommandService;
import com.example.wallet_api.application.WalletQueryService;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.support.ApiKeyTestSupport;

/**
 * Cadeia de seguranca real, sem a chave padrao dos outros testes: cada requisicao decide o que manda.
 */
@WebMvcTest(WalletController.class)
@Import(SecurityConfig.class)
class ApiKeySecurityTest {

    private static final String KEY = ApiKeyTestSupport.API_KEY;

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        ApiKeyTestSupport.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WalletQueryService queryService;

    @MockitoBean
    WalletCommandService commandService;

    private final Wallet wallet = new Wallet(1, null, new BigDecimal("0.00"), Instant.EPOCH, Instant.EPOCH);

    @Test
    void missingKeyIs401InTheStandardErrorFormat() throws Exception {
        mvc.perform(get("/wallets/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", containsString("X-API-Key")))
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.instance").value("/wallets/1"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "errada", " ", "" })
    void wrongKeyIs401WithTheSameAnswerAsAMissingOne(String wrong) throws Exception {
        mvc.perform(get("/wallets/1").header("X-API-Key", wrong))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Envie uma API key valida no header X-API-Key"));
    }

    @Test
    void keyMustMatchExactly() throws Exception {
        mvc.perform(get("/wallets/1").header("X-API-Key", KEY + "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/wallets/1").header("X-API-Key", KEY.substring(1))).andExpect(status().isUnauthorized());
        mvc.perform(get("/wallets/1").header("X-API-Key", KEY.toUpperCase())).andExpect(status().isUnauthorized());
    }

    @Test
    void validKeyIsAccepted() throws Exception {
        when(queryService.getWallet(1)).thenReturn(wallet);

        mvc.perform(get("/wallets/1").header("X-API-Key", KEY)).andExpect(status().isOk());
    }

    @Test
    void postWithoutKeyIs401NotACsrfError() throws Exception {
        mvc.perform(post("/wallets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void postWithKeyNeedsNoCsrfToken() throws Exception {
        when(commandService.createWallet(any())).thenReturn(wallet);

        mvc.perform(post("/wallets").header("X-API-Key", KEY))
                .andExpect(status().isCreated());
    }

    @Test
    void responsesAreNotCachedAndCreateNoSession() throws Exception {
        when(queryService.getWallet(anyLong())).thenReturn(wallet);
        when(commandService.createWallet(any())).thenReturn(wallet);

        mvc.perform(get("/wallets/1").header("X-API-Key", KEY))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/wallets").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
