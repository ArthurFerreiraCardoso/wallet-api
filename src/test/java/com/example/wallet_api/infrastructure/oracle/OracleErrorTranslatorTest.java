package com.example.wallet_api.infrastructure.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.UncategorizedSQLException;

import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.WalletException;

class OracleErrorTranslatorTest {

    private final OracleErrorTranslator translator = new OracleErrorTranslator();

    /** Do jeito que o Oracle entrega: codigo positivo e mensagem com ORA-, pilha do package e nomes internos. */
    private static SQLException oracleError(int code) {
        return new SQLException(
                "ORA-%05d: mensagem interna%nORA-06512: at \"WALLET_OWNER.PKG_WALLET\", line 142".formatted(code), "72000", code);
    }

    private static UncategorizedSQLException asSpringWouldWrap(SQLException e) {
        return new UncategorizedSQLException("CallableStatementCallback", "{call wallet_owner.pkg_wallet...}", e);
    }

    @ParameterizedTest
    @CsvSource({
            "20001, WALLET_NOT_FOUND",
            "20002, INVALID_AMOUNT",
            "20003, INVALID_TRANSACTION_TYPE",
            "20004, INSUFFICIENT_FUNDS",
            "20005, IDEMPOTENCY_KEY_CONFLICT",
            "20006, LEDGER_IMMUTABLE",
            "20007, WALLET_LOCKED",
            "20008, INVALID_IDEMPOTENCY_KEY",
            "20009, INVALID_HOLDER",
            "20010, BALANCE_LIMIT_EXCEEDED"
    })
    void translatesEveryCatalogCode(int oraCode, ErrorCode expected) {
        WalletException translated = translator.translate(asSpringWouldWrap(oracleError(oraCode))).orElseThrow();

        assertThat(translated.code()).isEqualTo(expected);
        assertThat(translated.getMessage())
                .isNotBlank()
                .doesNotContain("ORA-")
                .doesNotContain("WALLET_OWNER")
                .doesNotContain("PKG_WALLET")
                .doesNotContain("mensagem interna");
    }

    @Test
    void insufficientFundsGetsTheCatalogMessage() {
        WalletException translated = translator.translate(oracleError(20004)).orElseThrow();

        assertThat(translated.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(translated.getMessage()).isEqualTo("Saldo insuficiente para o debito");
    }

    @ParameterizedTest
    @ValueSource(ints = { 54, 30006 })
    void lockTimeoutThatEscapesThePackageIsAlsoContention(int oraCode) {
        assertThat(translator.translate(oracleError(oraCode))).get()
                .extracting(WalletException::code).isEqualTo(ErrorCode.WALLET_LOCKED);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 942, 1031, 2290, 6550, 12899, 20000, 20011, 20999 })
    void unknownErrorsAreNotTranslated(int oraCode) {
        assertThat(translator.translate(asSpringWouldWrap(oracleError(oraCode)))).isEmpty();
    }

    @Test
    void findsTheSqlExceptionAnywhereInTheCauseChain() {
        RuntimeException deep = new RuntimeException("camada externa", new IllegalStateException("meio", oracleError(20001)));

        assertThat(translator.translate(deep)).get().extracting(WalletException::code).isEqualTo(ErrorCode.WALLET_NOT_FOUND);
    }

    @Test
    void keepsTheOriginalErrorAsCauseForTheLog() {
        UncategorizedSQLException original = asSpringWouldWrap(oracleError(20004));

        assertThat(translator.translate(original).orElseThrow().getCause()).isSameAs(original);
    }

    @Test
    void errorsWithoutSqlExceptionAreNotTranslated() {
        assertThat(translator.translate(new IllegalStateException("sem banco"))).isEmpty();
    }
}
