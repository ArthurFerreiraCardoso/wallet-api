package com.example.wallet_api.application;

import org.springframework.stereotype.Service;

import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.PageResult;
import com.example.wallet_api.domain.Period;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletException;
import com.example.wallet_api.domain.WalletTransaction;
import com.example.wallet_api.domain.port.WalletQueryPort;

@Service
public class WalletQueryService {

    private final WalletQueryPort walletQueryPort;

    public WalletQueryService(WalletQueryPort walletQueryPort) {
        this.walletQueryPort = walletQueryPort;
    }

    public Wallet getWallet(long walletId) {
        return walletQueryPort.findWallet(walletId)
                .orElseThrow(() -> walletNotFound());
    }

    /** Carteira inexistente lanca WALLET_NOT_FOUND em vez de devolver pagina vazia. */
    public PageResult<WalletTransaction> listTransactions(long walletId, Period period, PageRequest pageRequest) {
        getWallet(walletId);
        return walletQueryPort.findTransactions(walletId, period, pageRequest);
    }

    private static WalletException walletNotFound() {
        return new WalletException(ErrorCode.WALLET_NOT_FOUND, "Carteira nao encontrada");
    }
}
