package com.example.wallet_api.domain;

/**
 * Resultado de registrar uma operacao. Em replay, {@code transaction} e o lancamento gravado na primeira chamada.
 */
public record RegisteredTransaction(WalletTransaction transaction, boolean replayed) {
}
