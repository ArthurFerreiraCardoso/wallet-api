package com.example.wallet_api.domain;

/**
 * Catalogo de erros de negocio e de validacao, com a mensagem que vai para o cliente da API.
 * Os que nascem no banco espelham o catalogo -20001..-20010 do PKG_WALLET (docs/ERROR-CODES.md).
 */
public enum ErrorCode {

    WALLET_NOT_FOUND("Carteira nao encontrada"),
    INVALID_AMOUNT("Valor deve ser maior que zero, ter no maximo 2 casas decimais e no maximo 99999999999999999.99"),
    INVALID_TRANSACTION_TYPE("Tipo de operacao deve ser CREDIT ou DEBIT"),
    INSUFFICIENT_FUNDS("Saldo insuficiente para o debito"),
    IDEMPOTENCY_KEY_CONFLICT("Chave de idempotencia ja usada nesta carteira para uma operacao diferente"),
    LEDGER_IMMUTABLE("Extrato e trilha de auditoria sao imutaveis"),
    WALLET_LOCKED("Carteira ocupada por outra operacao; tente novamente"),
    INVALID_IDEMPOTENCY_KEY("Header Idempotency-Key obrigatorio, com 1 a 64 caracteres entre letras, numeros, _ e -"),
    INVALID_HOLDER("Nome do titular deve ter no maximo 120 caracteres"),
    BALANCE_LIMIT_EXCEEDED("Credito excede o saldo maximo suportado"),

    INVALID_PERIOD("Periodo invalido"),
    VALIDATION_ERROR("Requisicao invalida");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
