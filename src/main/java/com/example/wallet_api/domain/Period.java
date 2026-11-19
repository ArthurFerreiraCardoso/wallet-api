package com.example.wallet_api.domain;

import java.time.Instant;

/**
 * Intervalo semiaberto [from, to). Qualquer um dos lados pode ficar em aberto.
 */
public record Period(Instant from, Instant to) {

    public static final Period ANY = new Period(null, null);

    /** Faixa que cabe num TIMESTAMP do Oracle sem ambiguidade de calendario. */
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    public Period {
        requireSupported(from);
        requireSupported(to);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new WalletException(ErrorCode.INVALID_PERIOD, "O inicio do periodo deve ser anterior ao fim");
        }
    }

    private static void requireSupported(Instant value) {
        if (value != null && (value.isBefore(MIN) || value.isAfter(MAX))) {
            throw new WalletException(ErrorCode.INVALID_PERIOD, "Datas do periodo devem estar entre os anos 0001 e 9999");
        }
    }
}
