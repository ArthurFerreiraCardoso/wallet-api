package com.example.wallet_api.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.wallet_api.domain.Wallet;

public record WalletResponse(long id, String holderName, BigDecimal balance, Instant createdAt, Instant updatedAt) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.holderName(), wallet.balance(), wallet.createdAt(), wallet.updatedAt());
    }
}
