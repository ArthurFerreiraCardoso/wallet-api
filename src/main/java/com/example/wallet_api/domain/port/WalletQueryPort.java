package com.example.wallet_api.domain.port;

import java.util.Optional;

import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.PageResult;
import com.example.wallet_api.domain.Period;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletTransaction;

public interface WalletQueryPort {

    Optional<Wallet> findWallet(long walletId);

    /** Extrato da carteira no periodo, do lancamento mais recente para o mais antigo. */
    PageResult<WalletTransaction> findTransactions(long walletId, Period period, PageRequest pageRequest);
}
