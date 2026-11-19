package com.example.wallet_api.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import com.example.wallet_api.support.WebLayerTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;

import com.example.wallet_api.application.WalletCommandService;
import com.example.wallet_api.application.WalletQueryService;
import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.RegisteredTransaction;
import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletException;
import com.example.wallet_api.domain.WalletTransaction;

/**
 * Contrato HTTP de escrita com o service mockado: status, headers e erros do catalogo.
 */
@WebMvcTest(WalletController.class)
class WalletWriteControllerTest extends WebLayerTest {

    private static final Instant CREATED = Instant.parse("2026-11-19T13:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WalletQueryService queryService;

    @MockitoBean
    WalletCommandService commandService;

    private static MockHttpServletRequestBuilder postTransaction(String key, String json) {
        MockHttpServletRequestBuilder request = post("/wallets/7/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static RegisteredTransaction result(boolean replayed) {
        return new RegisteredTransaction(new WalletTransaction(31, 7, TransactionType.CREDIT, new BigDecimal("10.50"),
                new BigDecimal("110.50"), "pedido-1", CREATED), replayed);
    }

    @Test
    void createsAWalletWith201AndLocation() throws Exception {
        when(commandService.createWallet("Maria")).thenReturn(new Wallet(42, "Maria", new BigDecimal("0.00"), CREATED, CREATED));

        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Maria\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/wallets/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(content().string(containsString("\"balance\":0.00")));
    }

    @Test
    void createsAWalletWithoutBody() throws Exception {
        when(commandService.createWallet(isNull())).thenReturn(new Wallet(43, null, new BigDecimal("0.00"), CREATED, CREATED));

        mvc.perform(post("/wallets"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holderName").doesNotExist());
    }

    @Test
    void appliedOperationIs201() throws Exception {
        when(commandService.registerTransaction(7, "CREDIT", new BigDecimal("10.50"), "pedido-1")).thenReturn(result(false));

        mvc.perform(postTransaction("pedido-1", "{\"type\":\"CREDIT\",\"amount\":10.50}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(content().string(containsString("\"balanceAfter\":110.50")));
    }

    @Test
    void replayIs200WithTheOriginalBodyAndAHeader() throws Exception {
        when(commandService.registerTransaction(7, "CREDIT", new BigDecimal("10.50"), "pedido-1")).thenReturn(result(true));

        mvc.perform(postTransaction("pedido-1", "{\"type\":\"CREDIT\",\"amount\":10.50}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(31));
    }

    @Test
    void insufficientFundsIs422() throws Exception {
        when(commandService.registerTransaction(anyLong(), any(), any(), any()))
                .thenThrow(new WalletException(ErrorCode.INSUFFICIENT_FUNDS, "Saldo insuficiente para o debito"));

        mvc.perform(postTransaction("d1", "{\"type\":\"DEBIT\",\"amount\":999}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente para o debito"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void reusedKeyIs409() throws Exception {
        when(commandService.registerTransaction(anyLong(), any(), any(), any()))
                .thenThrow(new WalletException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "Chave ja usada"));

        mvc.perform(postTransaction("pedido-1", "{\"type\":\"DEBIT\",\"amount\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void walletLockedIs503() throws Exception {
        when(commandService.registerTransaction(anyLong(), any(), any(), any()))
                .thenThrow(new WalletException(ErrorCode.WALLET_LOCKED, "Carteira ocupada por outra operacao; tente novamente"));

        mvc.perform(postTransaction("k", "{\"type\":\"CREDIT\",\"amount\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("WALLET_LOCKED"));
    }

    @Test
    void exhaustedConnectionPoolIs503InsteadOf500() throws Exception {
        when(commandService.registerTransaction(anyLong(), any(), any(), any()))
                .thenThrow(new CannotCreateTransactionException("Could not open JDBC Connection for transaction"));

        mvc.perform(postTransaction("k", "{\"type\":\"CREDIT\",\"amount\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("JDBC"))));
    }

    @Test
    void missingKeyGoesToTheDatabaseToBeRejectedWithTheCatalogCode() throws Exception {
        when(commandService.registerTransaction(anyLong(), any(), any(), isNull()))
                .thenThrow(new WalletException(ErrorCode.INVALID_IDEMPOTENCY_KEY, "Header Idempotency-Key obrigatorio"));

        mvc.perform(postTransaction(null, "{\"type\":\"CREDIT\",\"amount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void amountIsPassedWithoutRoundingSoTheDatabaseCanRejectTheScale() throws Exception {
        when(commandService.registerTransaction(7, "CREDIT", new BigDecimal("10.005"), "k"))
                .thenThrow(new WalletException(ErrorCode.INVALID_AMOUNT, "Valor invalido"));

        mvc.perform(postTransaction("k", "{\"type\":\"CREDIT\",\"amount\":10.005}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void missingFieldsAre400WithTheFieldList() throws Exception {
        mvc.perform(postTransaction("k", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field == 'type')].message").value("obrigatorio"))
                .andExpect(jsonPath("$.errors[?(@.field == 'amount')].message").value("obrigatorio"));

        verify(commandService, never()).registerTransaction(anyLong(), any(), any(), any());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(postTransaction("k", "{\"type\":\"CREDIT\",\"amount\":\"dez reais\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(postTransaction("k", "{nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /** Barrado em Java sem chegar ao driver, mas com o mesmo status e codigo que o banco daria. */
    @Test
    void hugeExponentGetsTheSameAnswerAsAnyInvalidAmount() throws Exception {
        mvc.perform(postTransaction("k", "{\"type\":\"CREDIT\",\"amount\":1e999999999}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        mvc.perform(postTransaction("k", "{\"type\":\"CREDIT\",\"amount\":10.00000000001}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        verify(commandService, never()).registerTransaction(anyLong(), any(), any(), any());
    }

    @Test
    void bodyLargerThan16KbIs413() throws Exception {
        mvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderName\":\"" + "a".repeat(17 * 1024) + "\"}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/wallets"));

        verify(commandService, never()).createWallet(any());
    }

    @Test
    void nonJsonContentTypeIs415() throws Exception {
        mvc.perform(post("/wallets/7/transactions").header("Idempotency-Key", "k")
                        .contentType(MediaType.TEXT_PLAIN).content("CREDIT 10"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
