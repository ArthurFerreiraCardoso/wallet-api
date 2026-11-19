package com.example.wallet_api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.wallet_api.support.OracleIntegrationTest;

/**
 * Escrita de ponta a ponta: HTTP -> service transacional -> PKG_WALLET (como WALLET_APP) -> tradutor de erros.
 */
class WalletWriteApiIT extends OracleIntegrationTest {

    @Autowired
    MockMvc mvc;

    private long walletId;

    @BeforeEach
    void createWallet() throws Exception {
        MvcResult created = mvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"  Joao Lima  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holderName").value("Joao Lima"))
                .andExpect(content().string(containsString("\"balance\":0.00")))
                .andReturn();
        walletId = Long.parseLong(created.getResponse().getHeader("Location").replace("/wallets/", ""));
    }

    private ResultActions postTransaction(String key, String type, String amount) throws Exception {
        var request = post("/wallets/" + walletId + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\",\"amount\":%s}".formatted(type, amount));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    /** Item obrigatorio do catalogo: nada interno do banco chega ao cliente. */
    private static void assertNoInternalDetails(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("ORA-").doesNotContain("WALLET_OWNER").doesNotContain("PKG_").doesNotContain("SQL");
    }

    @Test
    void createdWalletCanBeReadBack() throws Exception {
        mvc.perform(get("/wallets/" + walletId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderName").value("Joao Lima"));
    }

    @Test
    void creditsAndDebitsMoveTheBalance() throws Exception {
        postTransaction("c1", "CREDIT", "100.00").andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"balanceAfter\":100.00")));
        postTransaction("d1", "DEBIT", "30.25").andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.idempotencyKey").value("d1"))
                .andExpect(content().string(containsString("\"balanceAfter\":69.75")));

        mvc.perform(get("/wallets/" + walletId)).andExpect(content().string(containsString("\"balance\":69.75")));
        mvc.perform(get("/wallets/" + walletId + "/transactions")).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameResultAndAppliesOnce() throws Exception {
        String first = postTransaction("retry-1", "CREDIT", "25").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = postTransaction("retry-1", "CREDIT", "25.00")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        mvc.perform(get("/wallets/" + walletId)).andExpect(content().string(containsString("\"balance\":25.00")));

        // A resposta do POST bate com o que ficou gravado no extrato (inclusive createdAt).
        String statement = mvc.perform(get("/wallets/" + walletId + "/transactions"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(statement).contains(first);
    }

    @Test
    void sameKeyInAnotherWalletIsANewOperation() throws Exception {
        postTransaction("shared", "CREDIT", "5.00").andExpect(status().isCreated());
        long otherWallet = walletId;
        createWallet();
        assertThat(walletId).isNotEqualTo(otherWallet);

        postTransaction("shared", "CREDIT", "5.00")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    /** Ordem de validacao do package: tipo, valor, chave. Corpo invalido responde antes da chave ausente. */
    @Test
    void invalidBodyIsReportedBeforeAMissingKey() throws Exception {
        postTransaction(null, "CREDIT", "0")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void sameKeyForADifferentOperationIs409() throws Exception {
        postTransaction("k", "CREDIT", "25.00").andExpect(status().isCreated());

        ResultActions conflict = postTransaction("k", "CREDIT", "26.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertNoInternalDetails(conflict);
    }

    @Test
    void debitBeyondTheBalanceIs422AndKeepsTheBalance() throws Exception {
        postTransaction("c1", "CREDIT", "10.00").andExpect(status().isCreated());

        ResultActions rejected = postTransaction("d1", "DEBIT", "10.01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente para o debito"));
        assertNoInternalDetails(rejected);

        mvc.perform(get("/wallets/" + walletId)).andExpect(content().string(containsString("\"balance\":10.00")));
        mvc.perform(get("/wallets/" + walletId + "/transactions")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest(name = "[{index}] {0} {1} {2} -> {3} {4}")
    @CsvSource(nullValues = "NULL", value = {
            "k1,   CREDIT, 10.005, 422, INVALID_AMOUNT",
            "k2,   CREDIT, 0,      422, INVALID_AMOUNT",
            "k3,   CREDIT, -5,     422, INVALID_AMOUNT",
            "k4,   REFUND, 1,      422, INVALID_TRANSACTION_TYPE",
            "k5,   credit, 1,      422, INVALID_TRANSACTION_TYPE",
            "NULL, CREDIT, 1,      400, INVALID_IDEMPOTENCY_KEY",
            "'a b', CREDIT, 1,     400, INVALID_IDEMPOTENCY_KEY"
    })
    void businessValidationComesFromTheDatabaseWithCatalogCodes(String key, String type, String amount, int status,
                                                                String code) throws Exception {
        ResultActions result = postTransaction(key, type, amount)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertNoInternalDetails(result);
    }

    @Test
    void unknownWalletIs404() throws Exception {
        ResultActions result = mvc.perform(post("/wallets/-1/transactions")
                        .header("Idempotency-Key", "k").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CREDIT\",\"amount\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
        assertNoInternalDetails(result);
    }

    @Test
    void holderLongerThan120CharactersIs422() throws Exception {
        ResultActions result = mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderName\":\"" + "a".repeat(121) + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_HOLDER"));
        assertNoInternalDetails(result);
    }
}
