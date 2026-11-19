package com.example.wallet_api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DomainValidationTest {

    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void periodAcceptsOpenEnds() {
        assertThat(new Period(null, null)).isEqualTo(Period.ANY);
        assertThat(new Period(T0, null).from()).isEqualTo(T0);
        assertThat(new Period(null, T0).to()).isEqualTo(T0);
    }

    @Test
    void periodRequiresFromBeforeTo() {
        assertThat(new Period(T0, T0.plusSeconds(1)).to()).isAfter(T0);
        assertThatThrownBy(() -> new Period(T0, T0))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PERIOD));
        assertThatThrownBy(() -> new Period(T0.plusSeconds(1), T0)).isInstanceOf(WalletException.class);
    }

    @ParameterizedTest
    @CsvSource({ "+999999999-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "+10000-01-01T00:00:00Z" })
    void periodRejectsDatesOutsideTheDatabaseRange(String value) {
        Instant instant = Instant.parse(value);
        assertThatThrownBy(() -> new Period(instant, null))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PERIOD));
        assertThatThrownBy(() -> new Period(null, instant)).isInstanceOf(WalletException.class);
    }

    @ParameterizedTest
    @CsvSource({ "-1,20", "0,0", "0,101" })
    void pageRequestRejectsOutOfRangeValues(int page, int size) {
        assertThatThrownBy(() -> new PageRequest(page, size))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void pageRequestComputesOffset() {
        assertThat(new PageRequest(3, 25).offset()).isEqualTo(75);
        assertThat(new PageRequest(0, 100).offset()).isZero();
    }

    @ParameterizedTest
    @CsvSource({ "0,20,0", "1,20,1", "20,20,1", "21,20,2", "100,100,1" })
    void pageResultComputesTotalPages(long total, int size, int expectedPages) {
        assertThat(new PageResult<>(List.of(), 0, size, total).totalPages()).isEqualTo(expectedPages);
    }
}
