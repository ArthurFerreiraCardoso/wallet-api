package com.example.wallet_api.domain;

/**
 * Erro de negocio ou de validacao, com codigo do catalogo. A mensagem e escrita para o cliente da API: nunca carrega detalhe interno.
 */
public class WalletException extends RuntimeException {

    private final ErrorCode code;

    public WalletException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public WalletException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
