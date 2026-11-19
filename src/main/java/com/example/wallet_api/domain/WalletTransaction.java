package com.example.wallet_api.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletTransaction(long id, long walletId, TransactionType type, BigDecimal amount, BigDecimal balanceAfter,
                                String idempotencyKey, Instant createdAt) {
}
