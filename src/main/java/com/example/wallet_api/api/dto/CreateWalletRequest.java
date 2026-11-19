package com.example.wallet_api.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Corpo opcional de POST /wallets. O limite real do titular (120) e do banco (-20009, 422); o {@code @Size}
 * daqui so barra entrada absurda antes de ela chegar ao driver.
 */
public record CreateWalletRequest(@Size(max = 1000) String holderName) {
}
