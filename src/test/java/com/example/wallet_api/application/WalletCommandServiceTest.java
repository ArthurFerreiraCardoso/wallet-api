package com.example.wallet_api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.RegisteredTransaction;
import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletException;
import com.example.wallet_api.domain.WalletTransaction;
import com.example.wallet_api.domain.port.WalletCommandPort;
import com.example.wallet_api.domain.port.WalletQueryPort;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WalletCommandServiceTest {

    private final WalletCommandPort commandPort = mock(WalletCommandPort.class);
    private final WalletQueryPort queryPort = mock(WalletQueryPort.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WalletCommandService service = new WalletCommandService(commandPort, queryPort, meters);

    private double count(String outcome, String type) {
        var counter = meters.find(WalletCommandService.TRANSACTIONS_METRIC).tag("outcome", outcome).tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    private static RegisteredTransaction result(boolean replayed) {
        return new RegisteredTransaction(new WalletTransaction(5, 10, TransactionType.CREDIT,
                new BigDecimal("1.00"), new BigDecimal("1.00"), "k", Instant.EPOCH), replayed);
    }

    @Test
    void countsAppliedReplayedAndRejectedOperations() {
        when(commandPort.registerTransaction(10, "CREDIT", BigDecimal.ONE, "a")).thenReturn(result(false));
        when(commandPort.registerTransaction(10, "CREDIT", BigDecimal.ONE, "r")).thenReturn(result(true));
        when(commandPort.registerTransaction(10, "DEBIT", BigDecimal.TEN, "d"))
                .thenThrow(new WalletException(ErrorCode.INSUFFICIENT_FUNDS, "Saldo insuficiente para o debito"));

        service.registerTransaction(10, "CREDIT", BigDecimal.ONE, "a");
        service.registerTransaction(10, "CREDIT", BigDecimal.ONE, "r");
        assertThatThrownBy(() -> service.registerTransaction(10, "DEBIT", BigDecimal.TEN, "d")).isInstanceOf(WalletException.class);

        assertThat(count("applied", "CREDIT")).isEqualTo(1);
        assertThat(count("replayed", "CREDIT")).isEqualTo(1);
        assertThat(count("INSUFFICIENT_FUNDS", "DEBIT")).isEqualTo(1);
    }

    @Test
    void unknownTypeFromTheClientDoesNotBecomeAMetricTag() {
        when(commandPort.registerTransaction(10, "REFUND-123", BigDecimal.ONE, "k"))
                .thenThrow(new WalletException(ErrorCode.INVALID_TRANSACTION_TYPE, "Tipo invalido"));

        assertThatThrownBy(() -> service.registerTransaction(10, "REFUND-123", BigDecimal.ONE, "k")).isInstanceOf(WalletException.class);

        assertThat(count("INVALID_TRANSACTION_TYPE", "other")).isEqualTo(1);
        assertThat(meters.find(WalletCommandService.TRANSACTIONS_METRIC).tag("type", "REFUND-123").counter()).isNull();
    }

    @Test
    void createdWalletIsReadBackSoTheResponseMatchesWhatWasStored() {
        Wallet stored = new Wallet(10, "Maria", new BigDecimal("0.00"), Instant.EPOCH, Instant.EPOCH);
        when(commandPort.createWallet("  Maria  ")).thenReturn(10L);
        when(queryPort.findWallet(10)).thenReturn(Optional.of(stored));

        assertThat(service.createWallet("  Maria  ")).isEqualTo(stored);
    }

    @Test
    void registerReturnsWhatThePackageDecided() {
        RegisteredTransaction replay = result(true);
        when(commandPort.registerTransaction(10, "CREDIT", new BigDecimal("1.00"), "k")).thenReturn(replay);

        assertThat(service.registerTransaction(10, "CREDIT", new BigDecimal("1.00"), "k")).isSameAs(replay);
    }

    @Test
    void insufficientFundsFromTheDatabaseReachesTheCaller() {
        when(commandPort.registerTransaction(10, "DEBIT", new BigDecimal("50.00"), "d1"))
                .thenThrow(new WalletException(ErrorCode.INSUFFICIENT_FUNDS, "Saldo insuficiente para o debito"));

        assertThatThrownBy(() -> service.registerTransaction(10, "DEBIT", new BigDecimal("50.00"), "d1"))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
    }
}
