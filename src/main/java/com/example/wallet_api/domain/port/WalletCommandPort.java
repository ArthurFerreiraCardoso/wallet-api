package com.example.wallet_api.domain.port;

import java.math.BigDecimal;

import com.example.wallet_api.domain.RegisteredTransaction;

/**
 * Escrita. A implementacao delega ao PKG_WALLET, que valida, trava a carteira e resolve idempotencia;
 * erros de negocio chegam como WalletException.
 */
public interface WalletCommandPort {

    /** @return id da carteira criada */
    long createWallet(String holderName);

    /** Tipo e chave vao crus para o banco validar, que e a fonte unica dessas regras. */
    RegisteredTransaction registerTransaction(long walletId, String type, BigDecimal amount, String idempotencyKey);
}
