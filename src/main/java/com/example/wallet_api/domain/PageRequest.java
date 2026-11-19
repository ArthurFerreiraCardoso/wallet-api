package com.example.wallet_api.domain;

public record PageRequest(int page, int size) {

    public static final int MAX_SIZE = 100;

    public PageRequest {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new WalletException(ErrorCode.VALIDATION_ERROR,
                    "page deve ser >= 0 e size entre 1 e " + MAX_SIZE);
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
