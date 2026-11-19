package com.example.wallet_api.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.WalletTransaction;

import io.swagger.v3.oas.annotations.media.Schema;

public record TransactionResponse(
        @Schema(description = "Id do lancamento; cresce na ordem de aplicacao dentro da carteira", example = "31") long id,
        @Schema(example = "42") long walletId,
        TransactionType type,
        @Schema(description = "BRL, sempre com 2 casas", type = "number", example = "10.50") BigDecimal amount,
        @Schema(description = "Saldo logo apos este lancamento, 2 casas", type = "number", example = "160.50") BigDecimal balanceAfter,
        @Schema(example = "pedido-123") String idempotencyKey,
        @Schema(description = "ISO-8601 UTC", example = "2026-11-19T13:00:00.123456Z") Instant createdAt) {

    public static TransactionResponse from(WalletTransaction tx) {
        return new TransactionResponse(tx.id(), tx.walletId(), tx.type(), tx.amount(), tx.balanceAfter(),
                tx.idempotencyKey(), tx.createdAt());
    }
}
