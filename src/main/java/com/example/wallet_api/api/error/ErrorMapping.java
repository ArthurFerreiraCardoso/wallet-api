package com.example.wallet_api.api.error;

import org.springframework.http.HttpStatus;

import com.example.wallet_api.domain.ErrorCode;

/**
 * Status HTTP e titulo de cada erro. Criterio em docs/ERROR-CODES.md: 400 malformado, 404 inexistente,
 * 409 conflito com o que ja existe, 422 regra de negocio, 503 contencao passageira.
 *
 * @param exposeDetail se a mensagem do erro pode ir para o cliente; falso para erros que indicam falha interna
 */
record ErrorMapping(HttpStatus status, String title, boolean exposeDetail) {

    /** Codigos que nao vem do dominio: falhas de formato da requisicao e erros inesperados. */
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";
    static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    static final String REQUEST_ERROR = "REQUEST_ERROR";
    static final String SERVICE_BUSY = "SERVICE_BUSY";

    /** Switch sem default: um ErrorCode novo sem mapeamento nao compila. */
    static ErrorMapping of(ErrorCode code) {
        return switch (code) {
            case WALLET_NOT_FOUND -> client(HttpStatus.NOT_FOUND, "Carteira nao encontrada");
            case INVALID_AMOUNT -> client(HttpStatus.UNPROCESSABLE_CONTENT, "Valor invalido");
            case INVALID_TRANSACTION_TYPE -> client(HttpStatus.UNPROCESSABLE_CONTENT, "Tipo de operacao invalido");
            case INSUFFICIENT_FUNDS -> client(HttpStatus.UNPROCESSABLE_CONTENT, "Saldo insuficiente");
            case IDEMPOTENCY_KEY_CONFLICT -> client(HttpStatus.CONFLICT, "Chave de idempotencia ja usada");
            case WALLET_LOCKED -> client(HttpStatus.SERVICE_UNAVAILABLE, "Carteira ocupada");
            case INVALID_IDEMPOTENCY_KEY -> client(HttpStatus.BAD_REQUEST, "Chave de idempotencia invalida");
            case INVALID_HOLDER -> client(HttpStatus.UNPROCESSABLE_CONTENT, "Titular invalido");
            case BALANCE_LIMIT_EXCEEDED -> client(HttpStatus.UNPROCESSABLE_CONTENT, "Limite de saldo excedido");
            case INVALID_PERIOD -> client(HttpStatus.BAD_REQUEST, "Periodo invalido");
            case VALIDATION_ERROR -> client(HttpStatus.BAD_REQUEST, "Requisicao invalida");
            // So acontece por SQL direto no banco; pela API indica falha interna.
            case LEDGER_IMMUTABLE -> new ErrorMapping(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", false);
        };
    }

    private static ErrorMapping client(HttpStatus status, String title) {
        return new ErrorMapping(status, title, true);
    }
}
