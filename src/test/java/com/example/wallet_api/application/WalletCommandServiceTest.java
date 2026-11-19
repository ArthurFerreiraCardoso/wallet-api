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

class WalletCommandServiceTest {

    private final WalletCommandPort commandPort = mock(WalletCommandPort.class);
    private final WalletQueryPort queryPort = mock(WalletQueryPort.class);
    private final WalletCommandService service = new WalletCommandService(commandPort, queryPort);

    @Test
    void createdWalletIsReadBackSoTheResponseMatchesWhatWasStored() {
        Wallet stored = new Wallet(10, "Maria", new BigDecimal("0.00"), Instant.EPOCH, Instant.EPOCH);
        when(commandPort.createWallet("  Maria  ")).thenReturn(10L);
        when(queryPort.findWallet(10)).thenReturn(Optional.of(stored));

        assertThat(service.createWallet("  Maria  ")).isEqualTo(stored);
    }

    @Test
    void registerReturnsWhatThePackageDecided() {
        RegisteredTransaction replay = new RegisteredTransaction(new WalletTransaction(5, 10, TransactionType.CREDIT,
                new BigDecimal("1.00"), new BigDecimal("1.00"), "k", Instant.EPOCH), true);
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
