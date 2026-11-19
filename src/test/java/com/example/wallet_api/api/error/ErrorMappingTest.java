package com.example.wallet_api.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import com.example.wallet_api.domain.ErrorCode;

/** Contrato do catalogo documentado em docs/ERROR-CODES.md. */
class ErrorMappingTest {

    @ParameterizedTest
    @CsvSource({
            "WALLET_NOT_FOUND,         404, true",
            "INVALID_AMOUNT,           422, true",
            "INVALID_TRANSACTION_TYPE, 422, true",
            "INSUFFICIENT_FUNDS,       422, true",
            "IDEMPOTENCY_KEY_CONFLICT, 409, true",
            "LEDGER_IMMUTABLE,         500, false",
            "WALLET_LOCKED,            503, true",
            "INVALID_IDEMPOTENCY_KEY,  400, true",
            "INVALID_HOLDER,           422, true",
            "BALANCE_LIMIT_EXCEEDED,   422, true",
            "INVALID_PERIOD,           400, true",
            "VALIDATION_ERROR,         400, true"
    })
    void statusAndDetailExposureFollowTheCatalog(ErrorCode code, int status, boolean exposeDetail) {
        ErrorMapping mapping = ErrorMapping.of(code);
        assertThat(mapping.status()).isEqualTo(HttpStatus.valueOf(status));
        assertThat(mapping.exposeDetail()).isEqualTo(exposeDetail);
        assertThat(mapping.title()).isNotBlank();
    }
}
