package com.example.wallet_api.domain;

/**
 * Erros de negocio conhecidos pela aplicacao. Os que nascem no banco espelham o catalogo -20001..-20010
 * do PKG_WALLET (docs/ERROR-CODES.md); os demais sao de validacao da requisicao.
 */
public enum ErrorCode {

    WALLET_NOT_FOUND,
    INVALID_AMOUNT,
    INVALID_TRANSACTION_TYPE,
    INSUFFICIENT_FUNDS,
    IDEMPOTENCY_KEY_CONFLICT,
    LEDGER_IMMUTABLE,
    WALLET_LOCKED,
    INVALID_IDEMPOTENCY_KEY,
    INVALID_HOLDER,
    BALANCE_LIMIT_EXCEEDED,

    INVALID_PERIOD,
    VALIDATION_ERROR
}
