package com.example.wallet_api.infrastructure.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.WalletException;

/** Texto absurdo nem chega ao driver e recebe o mesmo codigo que o banco daria para a regra. */
class PkgWalletAdapterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PkgWalletAdapter adapter = new PkgWalletAdapter(jdbcTemplate, new OracleErrorTranslator());

    private static final String HUGE = "x".repeat(PkgWalletAdapter.MAX_TEXT_PARAMETER + 1);

    @Test
    void hugeHolderNameIsInvalidHolder() {
        assertThatThrownBy(() -> adapter.createWallet(HUGE))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code())
                        .isEqualTo(ErrorCode.INVALID_HOLDER));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void hugeTypeIsInvalidTransactionType() {
        assertThatThrownBy(() -> adapter.registerTransaction(1, HUGE, BigDecimal.ONE, "k"))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code())
                        .isEqualTo(ErrorCode.INVALID_TRANSACTION_TYPE));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void hugeKeyIsInvalidIdempotencyKey() {
        assertThatThrownBy(() -> adapter.registerTransaction(1, "CREDIT", BigDecimal.ONE, HUGE))
                .isInstanceOfSatisfying(WalletException.class, e -> assertThat(e.code())
                        .isEqualTo(ErrorCode.INVALID_IDEMPOTENCY_KEY));
        verifyNoInteractions(jdbcTemplate);
    }
}
