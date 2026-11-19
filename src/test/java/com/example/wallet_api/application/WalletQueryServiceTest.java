package com.example.wallet_api.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.PageResult;
import com.example.wallet_api.domain.Period;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletException;
import com.example.wallet_api.domain.WalletTransaction;
import com.example.wallet_api.domain.port.WalletQueryPort;

class WalletQueryServiceTest {

    private final WalletQueryPort port = mock(WalletQueryPort.class);
    private final WalletQueryService service = new WalletQueryService(port);

    private final Wallet wallet = new Wallet(1, null, new BigDecimal("10.00"), Instant.EPOCH, Instant.EPOCH);

    @Test
    void returnsTheWallet() {
        when(port.findWallet(1)).thenReturn(Optional.of(wallet));

        assertThat(service.getWallet(1)).isEqualTo(wallet);
    }

    @Test
    void unknownWalletIsNotFound() {
        when(port.findWallet(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWallet(99))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
    }

    @Test
    void statementOfUnknownWalletIsNotFoundInsteadOfAnEmptyPage() {
        when(port.findWallet(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listTransactions(99, Period.ANY, new PageRequest(0, 20)))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
        verify(port, never()).findTransactions(anyLong(), any(), any());
    }

    @Test
    void returnsTheStatementPage() {
        PageResult<WalletTransaction> page = new PageResult<>(List.of(), 0, 20, 0);
        when(port.findWallet(1)).thenReturn(Optional.of(wallet));
        when(port.findTransactions(1, Period.ANY, new PageRequest(0, 20))).thenReturn(page);

        assertThat(service.listTransactions(1, Period.ANY, new PageRequest(0, 20))).isSameAs(page);
    }
}
