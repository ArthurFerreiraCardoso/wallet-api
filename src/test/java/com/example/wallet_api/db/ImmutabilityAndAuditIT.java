package com.example.wallet_api.db;

import static com.example.wallet_api.support.PkgWalletCalls.appConnection;
import static com.example.wallet_api.support.PkgWalletCalls.createWallet;
import static com.example.wallet_api.support.PkgWalletCalls.register;
import static com.example.wallet_api.support.SqlAssertions.execute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.wallet_api.support.DbUser;
import com.example.wallet_api.support.OracleTestDatabase;
import com.example.wallet_api.support.PkgWalletCalls.RegisteredTransaction;

/**
 * Extrato imutavel (inclusive por SQL direto) e trilha de auditoria de saldo alimentada pelo banco, sem acesso da aplicacao.
 */
class ImmutabilityAndAuditIT {

    private record AuditRow(String dmlOperation, BigDecimal before, BigDecimal after, String source,
                            Long transactionId, String transactionType, Timestamp changedAt, String dbUser) {
    }

    private Connection app;
    private Connection owner;
    private long walletId;

    @BeforeEach
    void setUp() throws SQLException {
        app = appConnection();
        owner = OracleTestDatabase.connect(DbUser.OWNER);
        owner.setAutoCommit(false);
        walletId = createWallet(app, null).id();
        app.commit();
    }

    @AfterEach
    void tearDown() throws SQLException {
        app.rollback();
        app.close();
        owner.rollback();
        owner.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE wallet_transactions SET amount = amount + 1 WHERE wallet_id = %d",
            "UPDATE wallet_transactions SET amount = amount WHERE wallet_id = %d",
            "UPDATE wallet_transactions SET balance_after = 0 WHERE wallet_id = %d",
            "DELETE FROM wallet_transactions WHERE wallet_id = %d",
            """
            MERGE INTO wallet_transactions t USING (SELECT %d AS wallet_id FROM dual) s ON (t.wallet_id = s.wallet_id)
            WHEN MATCHED THEN UPDATE SET t.amount = 1 DELETE WHERE 1 = 1""",
            """
            INSERT INTO wallet_transactions (id, wallet_id, transaction_type, amount, balance_after, idempotency_key)
            VALUES (seq_wallet_transaction_id.NEXTVAL, %d, 'CREDIT', 1000, 1010, 'forged')"""
    })
    void ledgerCannotBeChangedOrForgedEvenByTheOwner(String sql) throws SQLException {
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.commit();

        assertImmutable(owner, sql.formatted(walletId));
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "TRUNCATE TABLE wallet_transactions", "TRUNCATE TABLE wallets" })
    void ledgerAndWalletsCannotBeTruncated(String sql) throws SQLException {
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.commit();

        assertImmutable(owner, sql);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void walletIdCannotChange() {
        assertImmutable(owner, "UPDATE wallets SET id = id + 1000000 WHERE id = " + walletId);
    }

    @Test
    void multiRowAndMergeBalanceChangesAreAuditedRowByRow() throws SQLException {
        long other = createWallet(app, null).id();
        app.commit();

        execute(owner, "UPDATE wallets SET balance = 7 WHERE id IN (%d, %d)".formatted(walletId, other));
        execute(owner, """
                MERGE INTO wallets w USING (SELECT %d AS id FROM dual) s ON (w.id = s.id)
                WHEN MATCHED THEN UPDATE SET w.balance = 9""".formatted(walletId));
        owner.commit();

        assertThat(auditRows()).extracting(AuditRow::after).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("7"), new BigDecimal("9"));
        assertThat(auditRows(other)).hasSize(1);
    }

    @Test
    void directUpdateAfterAPackageCallInTheSameSessionIsStillDirectSql() throws SQLException {
        // Uma chamada que da certo e outra que falha, ambas na sessao do owner, seguidas de UPDATE direto.
        register(owner, walletId, "CREDIT", "10.00", "c1");
        SQLException failure = catchThrowableOfType(SQLException.class, () -> register(owner, walletId, "DEBIT", "99.00", "d1"));
        assertThat((Throwable) failure).isNotNull();
        execute(owner, "UPDATE wallets SET balance = 123.45 WHERE id = " + walletId);
        owner.commit();

        List<AuditRow> audit = auditRows();
        assertThat(audit).extracting(AuditRow::source).containsExactly("PKG_WALLET", "DIRECT_SQL");
        assertThat(audit.getLast().transactionId()).isNull();
    }

    @Test
    void everyBalanceChangeThroughThePackageIsAudited() throws SQLException {
        RegisteredTransaction credit = register(app, walletId, "CREDIT", "100.00", "c1");
        RegisteredTransaction debit = register(app, walletId, "DEBIT", "30.50", "d1");
        app.commit();

        List<AuditRow> audit = auditRows();

        assertThat(audit).hasSize(2);
        AuditRow first = audit.get(0);
        assertThat(first.dmlOperation()).isEqualTo("UPDATE");
        assertThat(first.before()).isEqualByComparingTo("0");
        assertThat(first.after()).isEqualByComparingTo("100.00");
        assertThat(first.source()).isEqualTo("PKG_WALLET");
        assertThat(first.transactionId()).isEqualTo(credit.transactionId());
        assertThat(first.transactionType()).isEqualTo("CREDIT");
        assertThat(first.changedAt()).isNotNull();
        assertThat(first.dbUser()).isEqualTo("WALLET_APP");

        AuditRow second = audit.get(1);
        assertThat(second.before()).isEqualByComparingTo("100.00");
        assertThat(second.after()).isEqualByComparingTo("69.50");
        assertThat(second.transactionId()).isEqualTo(debit.transactionId());
        assertThat(second.transactionType()).isEqualTo("DEBIT");
    }

    @Test
    void creatingAWalletReplayingAndFailingDoNotProduceAuditRows() throws SQLException {
        register(app, walletId, "CREDIT", "10.00", "c1");
        register(app, walletId, "CREDIT", "10.00", "c1");
        SQLException rejected = catchThrowableOfType(SQLException.class, () -> register(app, walletId, "DEBIT", "50.00", "d1"));
        assertThat((Throwable) rejected).isNotNull();
        assertThat(rejected.getErrorCode()).isEqualTo(20004);
        app.commit();

        assertThat(auditRows()).hasSize(1);
    }

    @Test
    void directUpdateOfTheBalanceIsAuditedAsDirectSql() throws SQLException {
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.commit();

        execute(owner, "UPDATE wallets SET balance = 999.99 WHERE id = " + walletId);
        owner.commit();

        AuditRow last = auditRows().getLast();
        assertThat(last.dmlOperation()).isEqualTo("UPDATE");
        assertThat(last.before()).isEqualByComparingTo("10.00");
        assertThat(last.after()).isEqualByComparingTo("999.99");
        assertThat(last.source()).isEqualTo("DIRECT_SQL");
        assertThat(last.transactionId()).isNull();
        assertThat(last.transactionType()).isNull();
        assertThat(last.dbUser()).isEqualTo("WALLET_OWNER");
    }

    @Test
    void updateThatKeepsTheSameBalanceIsNotAChange() throws SQLException {
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.commit();

        execute(owner, "UPDATE wallets SET balance = balance WHERE id = " + walletId);
        owner.commit();

        assertThat(auditRows()).hasSize(1);
    }

    @Test
    void directInsertAndDeleteWithBalanceAreAudited() throws SQLException {
        long directId = nextWalletId();
        execute(owner, "INSERT INTO wallets (id, balance) VALUES (" + directId + ", 500)");
        execute(owner, "DELETE FROM wallets WHERE id = " + directId);
        owner.commit();

        List<AuditRow> audit = auditRows(directId);
        assertThat(audit).extracting(AuditRow::dmlOperation).containsExactly("INSERT", "DELETE");
        assertThat(audit.get(0).before()).isEqualByComparingTo("0");
        assertThat(audit.get(0).after()).isEqualByComparingTo("500");
        assertThat(audit.get(1).before()).isEqualByComparingTo("500");
        assertThat(audit.get(1).after()).isEqualByComparingTo("0");
        assertThat(audit).extracting(AuditRow::source).containsOnly("DIRECT_SQL");
    }

    @Test
    void auditRowsAreRolledBackTogetherWithTheChange() throws SQLException {
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.rollback();

        assertThat(auditRows()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE balance_audit SET balance_after = 0",
            "DELETE FROM balance_audit",
            "TRUNCATE TABLE balance_audit"
    })
    void auditTrailIsImmutableEvenForItsOwner(String sql) throws SQLException {
        register(app, walletId, "CREDIT", "10.00", "c1");
        app.commit();

        try (Connection audit = OracleTestDatabase.connect(DbUser.AUDIT)) {
            audit.setAutoCommit(false);
            assertImmutable(audit, sql);
            audit.rollback();
        }
        assertThat(auditRows()).hasSize(1);
    }

    @Test
    void ownerCannotWriteAuditRowsOutsideTheTrigger() throws SQLException {
        SQLException error = catchThrowableOfType(SQLException.class, () -> execute(owner, """
                BEGIN
                  wallet_audit.pkg_balance_audit.record_change(1, 'UPDATE', 0, 1000000, NULL, NULL);
                END;"""));
        assertThat((Throwable) error).isNotNull();
        assertThat(error.getErrorCode()).isEqualTo(6550);
        assertThat(error.getMessage()).contains("PLS-00904");

        SQLException insert = catchThrowableOfType(SQLException.class,
                () -> execute(owner, "INSERT INTO wallet_audit.balance_audit (id) VALUES (1)"));
        assertThat((Throwable) insert).isNotNull();
        assertThat(insert.getErrorCode()).isEqualTo(942);
    }

    private static void assertImmutable(Connection connection, String sql) {
        SQLException error = catchThrowableOfType(SQLException.class, () -> execute(connection, sql));
        assertThat((Throwable) error).as("deveria falhar: %s", sql).isNotNull();
        // Em trigger de linha o codigo e 20006; em trigger de DDL o Oracle embrulha em ORA-00604.
        assertThat(error.getMessage()).as(sql).contains("ORA-20006");
    }

    private List<AuditRow> auditRows() throws SQLException {
        return auditRows(walletId);
    }

    private static List<AuditRow> auditRows(long wallet) throws SQLException {
        List<AuditRow> rows = new ArrayList<>();
        try (Connection audit = OracleTestDatabase.connect(DbUser.AUDIT);
             PreparedStatement ps = audit.prepareStatement("""
                     SELECT dml_operation, balance_before, balance_after, change_source, transaction_id,
                            transaction_type, changed_at, db_user
                       FROM balance_audit
                      WHERE wallet_id = ?
                      ORDER BY id""")) {
            ps.setLong(1, wallet);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Long transactionId = rs.getObject(5, Long.class);
                    rows.add(new AuditRow(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getString(4),
                            transactionId, rs.getString(6), rs.getTimestamp(7), rs.getString(8)));
                }
            }
        }
        return rows;
    }

    private int ledgerCount() throws SQLException {
        try (PreparedStatement ps = app.prepareStatement("SELECT COUNT(*) FROM wallet_owner.v_wallet_transactions WHERE wallet_id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long nextWalletId() throws SQLException {
        try (PreparedStatement ps = owner.prepareStatement("SELECT seq_wallet_id.NEXTVAL FROM dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
