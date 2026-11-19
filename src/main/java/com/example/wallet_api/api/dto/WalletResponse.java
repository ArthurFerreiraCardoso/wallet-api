package com.example.wallet_api.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.wallet_api.domain.Wallet;

import io.swagger.v3.oas.annotations.media.Schema;

public record WalletResponse(
        @Schema(example = "42") long id,
        @Schema(nullable = true, example = "Maria Silva") String holderName,
        @Schema(description = "Saldo em BRL, sempre com 2 casas", type = "number", example = "150.50") BigDecimal balance,
        @Schema(description = "ISO-8601 UTC", example = "2026-11-19T13:00:00.123456Z") Instant createdAt,
        @Schema(description = "ISO-8601 UTC", example = "2026-11-19T13:05:00.654321Z") Instant updatedAt) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.holderName(), wallet.balance(), wallet.createdAt(), wallet.updatedAt());
    }
}
