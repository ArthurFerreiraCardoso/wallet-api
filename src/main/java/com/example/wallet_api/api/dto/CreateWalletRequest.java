package com.example.wallet_api.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Corpo opcional de POST /wallets. O limite do titular (120) e validado pelo banco (-20009, 422). */
public record CreateWalletRequest(
        @Schema(description = "Titular, opcional; so espacos conta como ausente", maxLength = 120, nullable = true,
                example = "Maria Silva")
        String holderName) {
}
