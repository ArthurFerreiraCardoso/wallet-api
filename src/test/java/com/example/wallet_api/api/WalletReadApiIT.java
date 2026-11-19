package com.example.wallet_api.api;

import static com.example.wallet_api.support.PkgWalletCalls.appConnection;
import static com.example.wallet_api.support.PkgWalletCalls.createWallet;
import static com.example.wallet_api.support.PkgWalletCalls.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.example.wallet_api.support.OracleIntegrationTest;
import com.example.wallet_api.support.PkgWalletCalls.RegisteredTransaction;

/**
 * Leitura de ponta a ponta: dados gravados pelo PKG_WALLET, lidos pela API conectada como WALLET_APP.
 */
class WalletReadApiIT extends OracleIntegrationTest {

    @Autowired
    MockMvc mvc;

    private long walletId;
    private final List<RegisteredTransaction> transactions = new ArrayList<>();

    @BeforeEach
    void seed() throws SQLException {
        try (Connection app = appConnection()) {
            walletId = createWallet(app, "Ana Souza").id();
            for (int i = 1; i <= 5; i++) {
                transactions.add(register(app, walletId, "CREDIT", i + "0.00", "seed-" + i));
                app.commit();
            }
            transactions.add(register(app, walletId, "DEBIT", "15.50", "seed-debit"));
            app.commit();
        }
    }

    @Test
    void returnsTheWalletWithTheCurrentBalance() throws Exception {
        mvc.perform(get("/wallets/" + walletId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(walletId))
                .andExpect(jsonPath("$.holderName").value("Ana Souza"))
                .andExpect(content().string(containsString("\"balance\":134.50")))
                .andExpect(jsonPath("$.createdAt").value(containsString("Z")));
    }

    @Test
    void unknownWalletIs404() throws Exception {
        mvc.perform(get("/wallets/-1")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
        mvc.perform(get("/wallets/-1/transactions")).andExpect(status().isNotFound());
    }

    @Test
    void statementIsPagedFromNewestToOldest() throws Exception {
        mvc.perform(get("/wallets/" + walletId + "/transactions").param("size", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].idempotencyKey").value("seed-debit"))
                .andExpect(jsonPath("$.content[0].type").value("DEBIT"))
                .andExpect(content().string(containsString("\"balanceAfter\":134.50")))
                .andExpect(jsonPath("$.content[3].idempotencyKey").value("seed-3"));

        mvc.perform(get("/wallets/" + walletId + "/transactions").param("size", "4").param("page", "1"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].idempotencyKey").value("seed-2"))
                .andExpect(jsonPath("$.content[1].idempotencyKey").value("seed-1"));

        mvc.perform(get("/wallets/" + walletId + "/transactions").param("size", "4").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(6));
    }

    @Test
    void periodFilterIsHalfOpen() throws Exception {
        Instant second = createdAt(1);
        Instant third = createdAt(2);
        Instant fourth = createdAt(3);
        // Premissa do teste: commits separados geram created_at estritamente crescentes (TIMESTAMP(6)).
        assertThat(second).isBefore(third);
        assertThat(third).isBefore(fourth);

        // [segundo, quarto): inclui o segundo e o terceiro, exclui o quarto.
        mvc.perform(get("/wallets/" + walletId + "/transactions")
                        .param("from", second.toString())
                        .param("to", fourth.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].idempotencyKey").value("seed-3"))
                .andExpect(jsonPath("$.content[1].idempotencyKey").value("seed-2"));

        mvc.perform(get("/wallets/" + walletId + "/transactions").param("from", fourth.toString()))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    private Instant createdAt(int index) {
        return transactions.get(index).createdAt().toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
