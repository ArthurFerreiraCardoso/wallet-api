package com.example.wallet_api.db;

import static com.example.wallet_api.support.PkgWalletCalls.appConnection;
import static com.example.wallet_api.support.PkgWalletCalls.createWallet;
import static com.example.wallet_api.support.PkgWalletCalls.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import com.example.wallet_api.support.DbUser;
import com.example.wallet_api.support.OracleTestDatabase;
import com.example.wallet_api.support.SqlAssertions;
import com.example.wallet_api.support.PkgWalletCalls.CreatedWallet;
import com.example.wallet_api.support.PkgWalletCalls.RegisteredTransaction;

/**
 * Regras do PKG_WALLET chamadas como WALLET_APP, cada teste com a propria carteira.
 */
class PkgWalletIT {

    private Connection app;

    @BeforeEach
    void connect() throws SQLException {
        app = appConnection();
    }

    @AfterEach
    void close() throws SQLException {
        app.rollback();
        app.close();
    }

    @Test
    void createsWalletWithZeroBalanceAndTrimmedHolder() throws SQLException {
        CreatedWallet wallet = createWallet(app, "  Maria Silva  ");
        app.commit();

        assertThat(wallet.id()).isPositive();
        assertThat(wallet.balance()).isEqualByComparingTo("0");
        assertThat(wallet.createdAt()).isNotNull();
        assertThat(holderOf(wallet.id())).isEqualTo("Maria Silva");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void holderIsOptional(String holder) throws SQLException {
        CreatedWallet wallet = createWallet(app, holder);
        assertThat(holderOf(wallet.id())).isNull();
    }

    @Test
    void rejectsHolderLongerThan120Characters() {
        assertPkgError(20009, () -> createWallet(app, "a".repeat(121)));
    }

    @Test
    void creditAndDebitMoveTheBalanceAndAreRecorded() throws SQLException {
        long walletId = newWallet();

        RegisteredTransaction credit = register(app, walletId, "CREDIT", "100.50", "c1");
        RegisteredTransaction debit = register(app, walletId, "DEBIT", "40.25", "d1");
        app.commit();

        assertThat(credit.balanceAfter()).isEqualByComparingTo("100.50");
        assertThat(debit.balanceAfter()).isEqualByComparingTo("60.25");
        assertThat(credit.replayed()).isFalse();
        assertThat(debit.transactionId()).isGreaterThan(credit.transactionId());
        assertThat(balanceOf(walletId)).isEqualByComparingTo("60.25");
        assertThat(transactionCount(walletId)).isEqualTo(2);
    }

    @Test
    void debitCanTakeTheBalanceExactlyToZero() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "10.00", "c1");

        assertThat(register(app, walletId, "DEBIT", "10.00", "d1").balanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsDebitThatWouldMakeTheBalanceNegativeAndKeepsEverythingIntact() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.commit();

        assertPkgError(20004, () -> register(app, walletId, "DEBIT", "10.01", "d1"));
        app.commit();

        assertThat(balanceOf(walletId)).isEqualByComparingTo("10.00");
        assertThat(transactionCount(walletId)).isEqualTo(1);
    }

    @Test
    void replayWithSameKeyAndPayloadReturnsTheOriginalResultWithoutApplyingAgain() throws SQLException {
        long walletId = newWallet();
        RegisteredTransaction first = register(app, walletId, "CREDIT", "25.00", "retry-1");
        app.commit();
        register(app, walletId, "CREDIT", "5.00", "other");
        app.commit();

        RegisteredTransaction replay = register(app, walletId, "CREDIT", "25.00", "retry-1");
        app.commit();

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(replay.balanceAfter()).isEqualByComparingTo(first.balanceAfter());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(balanceOf(walletId)).isEqualByComparingTo("30.00");
        assertThat(transactionCount(walletId)).isEqualTo(2);
    }

    @Test
    void replayIgnoresTrailingZerosInTheAmount() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "25", "k");

        assertThat(register(app, walletId, "CREDIT", "25.00", "k").replayed()).isTrue();
    }

    @Test
    void sameKeyWithDifferentAmountIsAConflict() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "25.00", "k");

        assertPkgError(20005, () -> register(app, walletId, "CREDIT", "25.01", "k"));
    }

    @Test
    void sameKeyWithDifferentTypeIsAConflict() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "25.00", "k");

        assertPkgError(20005, () -> register(app, walletId, "DEBIT", "25.00", "k"));
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsRejectedEvenWhenItWouldRoundToAPreviousPayload() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "10.01", "k");

        assertPkgError(20002, () -> register(app, walletId, "CREDIT", "10.005", "k"));
        assertPkgError(20002, () -> register(app, walletId, "CREDIT", "10.005", "k2"));
    }

    @Test
    void sameKeyInAnotherWalletIsAnIndependentOperation() throws SQLException {
        long a = newWallet();
        long b = newWallet();

        RegisteredTransaction inA = register(app, a, "CREDIT", "1.00", "shared");
        RegisteredTransaction inB = register(app, b, "CREDIT", "2.00", "shared");

        assertThat(inB.replayed()).isFalse();
        assertThat(inB.transactionId()).isNotEqualTo(inA.transactionId());
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "0.001", "10.005", "100000000000000000" })
    void rejectsInvalidAmounts(String amount) throws SQLException {
        long walletId = newWallet();
        assertPkgError(20002, () -> register(app, walletId, "CREDIT", amount, "k"));
    }

    @Test
    void rejectsNullAmount() throws SQLException {
        long walletId = newWallet();
        assertPkgError(20002, () -> register(app, walletId, "CREDIT", (BigDecimal) null, "k"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "credit", "REFUND", "TRANSFER" })
    void rejectsInvalidTypes(String type) throws SQLException {
        long walletId = newWallet();
        assertPkgError(20003, () -> register(app, walletId, type, "1.00", "k"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "com espaco", "acentuação", "a/b", "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk" })
    void rejectsInvalidIdempotencyKeys(String key) throws SQLException {
        long walletId = newWallet();
        assertPkgError(20008, () -> register(app, walletId, "CREDIT", "1.00", key));
    }

    @Test
    void rejectsUnknownWallet() {
        assertPkgError(20001, () -> register(app, -1, "CREDIT", "1.00", "k"));
    }

    @Test
    void rejectsCreditThatWouldExceedTheMaximumBalance() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "99999999999999999.99", "max");

        assertPkgError(20010, () -> register(app, walletId, "CREDIT", "0.01", "over"));
    }

    @Test
    void packageDoesNotCommitTheCallerControlsTheTransaction() throws SQLException {
        long walletId = newWallet();
        app.commit();

        register(app, walletId, "CREDIT", "50.00", "k");
        app.rollback();

        assertThat(balanceOf(walletId)).isEqualByComparingTo("0");
        assertThat(transactionCount(walletId)).isZero();
    }

    @Test
    void secondSessionWaitsForTheLockAndSeesTheCommittedBalance() throws Exception {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.commit();

        try (Connection other = appConnection()) {
            register(app, walletId, "DEBIT", "10.00", "d1");

            CompletableFuture<Integer> concurrentDebit = CompletableFuture.supplyAsync(() -> {
                try {
                    register(other, walletId, "DEBIT", "10.00", "d2");
                    other.commit();
                    return 0;
                } catch (SQLException e) {
                    rollbackQuietly(other);
                    return e.getErrorCode();
                }
            });

            // Com a linha travada pela primeira sessao, a segunda nao pode terminar antes do commit
            // (nem do timeout de 5 s). Se terminar aqui, o lock nao esta funcionando.
            assertThatThrownBy(() -> concurrentDebit.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            app.commit();

            // Liberada, ela decide sobre o saldo ja zerado e recusa o debito.
            assertThat(concurrentDebit.get(10, TimeUnit.SECONDS)).as("debito apos o saldo zerar").isEqualTo(20004);
            assertThat(balanceOf(walletId)).isEqualByComparingTo("0");
        }
    }

    @Test
    void idempotencyKeyIsCaseSensitive() throws SQLException {
        long walletId = newWallet();

        RegisteredTransaction upper = register(app, walletId, "CREDIT", "1.00", "K");
        RegisteredTransaction lower = register(app, walletId, "CREDIT", "1.00", "k");

        assertThat(lower.replayed()).isFalse();
        assertThat(lower.transactionId()).isNotEqualTo(upper.transactionId());
    }

    @Test
    void keyComparisonIgnoresALinguisticSessionNls() throws SQLException {
        long walletId = newWallet();
        RegisteredTransaction upper = register(app, walletId, "CREDIT", "1.00", "Key");
        SqlAssertions.execute(app, "ALTER SESSION SET NLS_COMP = LINGUISTIC NLS_SORT = BINARY_CI");
        try {
            RegisteredTransaction lower = register(app, walletId, "CREDIT", "1.00", "key");
            assertThat(lower.replayed()).as("'key' nao e replay de 'Key'").isFalse();
            assertThat(lower.transactionId()).isNotEqualTo(upper.transactionId());
            // Com as duas chaves gravadas, a busca continua achando exatamente uma (sem ORA-01422).
            assertThat(register(app, walletId, "CREDIT", "1.00", "Key").transactionId()).isEqualTo(upper.transactionId());
        } finally {
            SqlAssertions.execute(app, "ALTER SESSION SET NLS_COMP = BINARY NLS_SORT = BINARY");
        }
    }

    @Test
    void replayOfADebitStillWorksAfterTheBalanceDropped() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "10.00", "c1");
        RegisteredTransaction debit = register(app, walletId, "DEBIT", "10.00", "d1");

        RegisteredTransaction replay = register(app, walletId, "DEBIT", "10.00", "d1");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transactionId()).isEqualTo(debit.transactionId());
    }

    @Test
    void acceptsBoundaryValues() throws SQLException {
        assertThat(createWallet(app, "a".repeat(120)).id()).isPositive();

        long walletId = newWallet();
        assertThat(register(app, walletId, "CREDIT", "0.01", "k".repeat(64)).balanceAfter()).isEqualByComparingTo("0.01");
    }

    @Test
    void validatesTheRequestBeforeLookingUpTheWallet() {
        assertPkgError(20002, () -> register(app, -1, "CREDIT", "0", "k"));
    }

    @Test
    void failedCallLeavesNothingBehindEvenIfTheCallerSwallowsTheErrorAndCommits() throws SQLException {
        long walletId = newWallet();
        register(app, walletId, "CREDIT", "5.00", "c1");
        app.commit();

        SqlAssertions.execute(app, """
                DECLARE
                  v_id NUMBER; v_balance NUMBER; v_created TIMESTAMP; v_replayed VARCHAR2(1);
                BEGIN
                  wallet_owner.pkg_wallet.register_transaction(%d, 'DEBIT', 6, 'swallowed', v_id, v_balance, v_created, v_replayed);
                EXCEPTION
                  WHEN OTHERS THEN NULL;
                END;""".formatted(walletId));
        app.commit();

        assertThat(balanceOf(walletId)).isEqualByComparingTo("5.00");
        assertThat(transactionCount(walletId)).isEqualTo(1);
    }

    @Test
    void givesUpWithWalletLockedWhenTheLockIsHeldForTooLong() throws Exception {
        long walletId = newWallet();
        app.commit();
        register(app, walletId, "CREDIT", "1.00", "holder");

        try (Connection other = appConnection()) {
            long start = System.nanoTime();
            assertPkgError(20007, () -> register(other, walletId, "CREDIT", "1.00", "waiter"));
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isBetween(4L, 15L);
            other.rollback();
        }
    }

    @Test
    void auditContextCannotBeSetOutsideThePackageEvenByTheOwner() throws SQLException {
        try (Connection owner = OracleTestDatabase.connect(DbUser.OWNER)) {
            SQLException error = catchThrowableOfType(SQLException.class,
                    () -> SqlAssertions.execute(owner, "BEGIN pkg_wallet_context.set_current(1, 'CREDIT'); END;"));
            assertThat(error.getErrorCode()).isEqualTo(6550);
            assertThat(error.getMessage()).contains("PLS-00904");
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // a conexao sera fechada pelo teste
        }
    }

    private long newWallet() throws SQLException {
        return createWallet(app, null).id();
    }

    private static void assertPkgError(int expectedCode, ThrowingCallable call) {
        SQLException error = catchThrowableOfType(SQLException.class, call);
        assertThat((Throwable) error).as("esperava ORA-%d", expectedCode).isNotNull();
        assertThat(error.getErrorCode()).as(error.getMessage()).isEqualTo(expectedCode);
    }

    private BigDecimal balanceOf(long walletId) throws SQLException {
        try (PreparedStatement ps = app.prepareStatement("SELECT balance FROM wallet_owner.v_wallets WHERE id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private String holderOf(long walletId) throws SQLException {
        try (PreparedStatement ps = app.prepareStatement("SELECT holder_name FROM wallet_owner.v_wallets WHERE id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private int transactionCount(long walletId) throws SQLException {
        try (PreparedStatement ps = app.prepareStatement("SELECT COUNT(*) FROM wallet_owner.v_wallet_transactions WHERE wallet_id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
