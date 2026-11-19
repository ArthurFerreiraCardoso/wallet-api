package com.example.wallet_api.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.wallet_api.application.WalletCommandService;
import com.example.wallet_api.application.WalletQueryService;
import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.PageResult;
import com.example.wallet_api.domain.Period;
import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletException;
import com.example.wallet_api.domain.WalletTransaction;

/**
 * Contrato HTTP de leitura e formato padronizado dos erros, com o service mockado.
 */
@WebMvcTest(WalletController.class)
@WithMockUser
class WalletControllerTest {

    private static final Instant CREATED = Instant.parse("2026-11-19T13:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WalletQueryService service;

    @MockitoBean
    WalletCommandService commandService;

    @Test
    void returnsTheWallet() throws Exception {
        when(service.getWallet(7)).thenReturn(new Wallet(7, "Maria", new BigDecimal("150.50"), CREATED, CREATED));

        mvc.perform(get("/wallets/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.holderName").value("Maria"))
                .andExpect(content().string(containsString("\"balance\":150.50")))
                .andExpect(jsonPath("$.createdAt").value("2026-11-19T13:00:00Z"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void unknownWalletIsAProblemDetailWithCodeAndTraceId() throws Exception {
        when(service.getWallet(anyLong())).thenThrow(new WalletException(ErrorCode.WALLET_NOT_FOUND, "Carteira nao encontrada"));

        mvc.perform(get("/wallets/99"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Carteira nao encontrada"))
                .andExpect(jsonPath("$.detail").value("Carteira nao encontrada"))
                .andExpect(jsonPath("$.instance").value("/wallets/99"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void returnsTheStatementPageWithDefaults() throws Exception {
        WalletTransaction tx = new WalletTransaction(31, 7, TransactionType.DEBIT, new BigDecimal("20.00"),
                new BigDecimal("130.50"), "pedido-123", CREATED);
        when(service.listTransactions(7, Period.ANY, new PageRequest(0, 20)))
                .thenReturn(new PageResult<>(List.of(tx), 0, 20, 41));

        mvc.perform(get("/wallets/7/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(31))
                .andExpect(jsonPath("$.content[0].type").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].idempotencyKey").value("pedido-123"))
                .andExpect(content().string(containsString("\"amount\":20.00")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(41))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void passesThePeriodAndPageToTheService() throws Exception {
        Period period = new Period(Instant.parse("2026-11-01T03:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"));
        when(service.listTransactions(eq(7L), eq(period), eq(new PageRequest(2, 5))))
                .thenReturn(new PageResult<>(List.of(), 2, 5, 0));

        mvc.perform(get("/wallets/7/transactions")
                        .param("page", "2").param("size", "5")
                        .param("from", "2026-11-01T00:00:00-03:00")
                        .param("to", "2026-11-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = { "page=-1", "size=0", "size=101" })
    void outOfRangePagingIsABadRequest(String query) throws Exception {
        mvc.perform(get("/wallets/7/transactions?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void periodWithFromAfterToIsABadRequest() throws Exception {
        mvc.perform(get("/wallets/7/transactions")
                        .param("from", "2026-11-02T00:00:00Z").param("to", "2026-11-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/wallets/abc", "/wallets/7/transactions?from=ontem", "/wallets/7/transactions?size=muitos" })
    void malformedParametersAreABadRequestWithoutJavaDetails(String uri) throws Exception {
        mvc.perform(get(uri))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void unexpectedErrorIsAGeneric500WithoutInternalDetails() throws Exception {
        when(service.getWallet(anyLong())).thenThrow(new IllegalStateException("ORA-00942: segredo interno em WALLET_OWNER"));

        mvc.perform(get("/wallets/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("ORA-"))))
                .andExpect(content().string(not(containsString("WALLET_OWNER"))));
    }

    @Test
    void internalBusinessErrorHidesItsMessage() throws Exception {
        when(service.getWallet(anyLong())).thenThrow(new WalletException(ErrorCode.LEDGER_IMMUTABLE, "detalhe interno do trigger"));

        mvc.perform(get("/wallets/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("LEDGER_IMMUTABLE"))
                .andExpect(content().string(not(containsString("detalhe interno"))));
    }

    @Test
    void walletLockedIsA503ThatKeepsItsMessage() throws Exception {
        when(service.getWallet(anyLong())).thenThrow(new WalletException(ErrorCode.WALLET_LOCKED, "Carteira ocupada; tente novamente"));

        mvc.perform(get("/wallets/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WALLET_LOCKED"))
                .andExpect(jsonPath("$.detail").value("Carteira ocupada; tente novamente"));
    }

    @Test
    void typeMismatchNamesTheParameter() throws Exception {
        mvc.perform(get("/wallets/7/transactions?page=x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Valor invalido para o parametro 'page'"));
    }

    @Test
    void unknownRouteIsAProblemDetail() throws Exception {
        mvc.perform(get("/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
