package com.example.wallet_api.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.WalletTransaction;

public record TransactionResponse(long id, long walletId, TransactionType type, BigDecimal amount, BigDecimal balanceAfter,
                                  String idempotencyKey, Instant createdAt) {

    public static TransactionResponse from(WalletTransaction tx) {
        return new TransactionResponse(tx.id(), tx.walletId(), tx.type(), tx.amount(), tx.balanceAfter(),
                tx.idempotencyKey(), tx.createdAt());
    }
}
