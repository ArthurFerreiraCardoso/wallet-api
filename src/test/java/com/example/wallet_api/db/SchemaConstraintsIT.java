package com.example.wallet_api.db;

import static com.example.wallet_api.support.SqlAssertions.assertOraError;
import static com.example.wallet_api.support.SqlAssertions.execute;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.wallet_api.support.DbUser;
import com.example.wallet_api.support.OracleTestDatabase;

/**
 * Constraints como defesa em profundidade: mesmo SQL executado direto pelo owner nao viola as invariantes.
 * Cada teste roda numa transacao desfeita no final.
 */
class SchemaConstraintsIT {

    private static final int CHECK_VIOLATED = 2290;
    private static final int UNIQUE_VIOLATED = 1;
    private static final int PARENT_KEY_NOT_FOUND = 2291;
    private static final int CANNOT_UPDATE_TO_NULL = 1407;
    private static final int VALUE_TOO_LARGE = 12899;
    private static final int READ_ONLY_VIEW = 42399;

    private Connection owner;
    private long walletId;

    @BeforeEach
    void setUp() throws SQLException {
        owner = OracleTestDatabase.connect(DbUser.OWNER);
        owner.setAutoCommit(false);
        walletId = nextVal("seq_wallet_id");
        execute(owner, "INSERT INTO wallets (id) VALUES (" + walletId + ")");
    }

    @AfterEach
    void tearDown() throws SQLException {
        owner.rollback();
        owner.close();
    }

    @Test
    void newWalletStartsWithZeroBalance() throws SQLException {
        assertThat(queryString("SELECT TO_CHAR(balance, 'FM0.00') FROM wallets WHERE id = " + walletId)).isEqualTo("0.00");
    }

    @ParameterizedTest
    @CsvSource({
            "WALLETS,             CREATED_AT",
            "WALLETS,             UPDATED_AT",
            "WALLET_TRANSACTIONS, CREATED_AT"
    })
    void timestampsDefaultToUtc(String table, String column) throws SQLException {
        String dataDefault = queryString("SELECT data_default FROM user_tab_columns WHERE table_name = '%s' AND column_name = '%s'"
                .formatted(table, column));
        assertThat(dataDefault).containsIgnoringCase("SYS_EXTRACT_UTC(SYSTIMESTAMP)");
    }

    @Test
    void balanceIsMandatory() {
        assertOraError(owner, "UPDATE wallets SET balance = NULL WHERE id = " + walletId, CANNOT_UPDATE_TO_NULL);
    }

    @Test
    void holderNameIsLimitedTo120Characters() {
        assertOraError(owner, "UPDATE wallets SET holder_name = RPAD('a', 121, 'a') WHERE id = " + walletId, VALUE_TOO_LARGE);
    }

    @Test
    void readViewsRejectWritesEvenFromTheOwner() {
        assertOraError(owner, "UPDATE v_wallets SET balance = 1 WHERE id = " + walletId, READ_ONLY_VIEW);
    }

    @Test
    void balanceCannotBeNegative() {
        SQLException error = assertOraError(owner, "UPDATE wallets SET balance = -0.01 WHERE id = " + walletId, CHECK_VIOLATED);
        assertThat(error.getMessage()).contains("CK_WALLETS_BALANCE_NON_NEGATIVE");
    }

    @Test
    void holderNameCannotBeBlank() {
        SQLException error = assertOraError(owner, "UPDATE wallets SET holder_name = '   ' WHERE id = " + walletId, CHECK_VIOLATED);
        assertThat(error.getMessage()).contains("CK_WALLETS_HOLDER_NAME_NOT_BLANK");
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "0.004" })
    void amountMustBePositive(String amount) {
        SQLException error = assertOraError(owner, insertTransaction("CREDIT", amount, "10", "k-amount"), CHECK_VIOLATED);
        assertThat(error.getMessage()).contains("CK_WTX_AMOUNT_POSITIVE");
    }

    /**
     * Limitacao conhecida do NUMBER(19,2): mais de 2 casas sao arredondadas antes do CHECK.
     * Por isso a validacao de escala e responsabilidade do PKG_WALLET, que rejeita o valor antes de gravar.
     */
    @Test
    void amountColumnRoundsExtraDecimalPlaces() throws SQLException {
        execute(owner, insertTransaction("CREDIT", "10.005", "10.01", "k-scale"));

        assertThat(queryString("SELECT TO_CHAR(amount, 'FM999999990.00') FROM wallet_transactions WHERE idempotency_key = 'k-scale'"))
                .isEqualTo("10.01");
    }

    @ParameterizedTest
    @ValueSource(strings = { " ", "has space", "acentuação", "a/b", "k;drop" })
    void idempotencyKeyMustMatchTheAllowedFormat(String key) {
        SQLException error = assertOraError(owner, insertTransaction("CREDIT", "1", "1", key), CHECK_VIOLATED);
        assertThat(error.getMessage()).contains("CK_WTX_IDEMPOTENCY_KEY_FORMAT");
    }

    @Test
    void idempotencyKeyIsLimitedTo64Characters() throws SQLException {
        execute(owner, insertTransaction("CREDIT", "1", "1", "k".repeat(64)));
        assertOraError(owner, insertTransaction("CREDIT", "1", "1", "k".repeat(65)), VALUE_TOO_LARGE);
    }

    @Test
    void typeMustBeCreditOrDebit() {
        SQLException error = assertOraError(owner, insertTransaction("REFUND", "1", "10", "k-type"), CHECK_VIOLATED);
        assertThat(error.getMessage()).contains("CK_WTX_TYPE");
    }

    @Test
    void balanceAfterCannotBeNegative() {
        SQLException error = assertOraError(owner, insertTransaction("DEBIT", "1", "-1", "k-neg"), CHECK_VIOLATED);
        assertThat(error.getMessage()).contains("CK_WTX_BALANCE_AFTER_NON_NEGATIVE");
    }

    @Test
    void idempotencyKeyIsUniquePerWallet() throws SQLException {
        execute(owner, insertTransaction("CREDIT", "10", "10", "same-key"));

        SQLException error = assertOraError(owner, insertTransaction("CREDIT", "10", "20", "same-key"), UNIQUE_VIOLATED);
        assertThat(error.getMessage()).contains("UK_WTX_WALLET_IDEMPOTENCY_KEY");
    }

    @Test
    void sameIdempotencyKeyIsAllowedInAnotherWallet() throws SQLException {
        long otherWallet = nextVal("seq_wallet_id");
        execute(owner, "INSERT INTO wallets (id) VALUES (" + otherWallet + ")");

        execute(owner, insertTransaction("CREDIT", "10", "10", "shared-key"));
        execute(owner, insertTransaction(otherWallet, "CREDIT", "10", "10", "shared-key"));
    }

    @Test
    void transactionRequiresExistingWallet() {
        SQLException error = assertOraError(owner, insertTransaction(-42, "CREDIT", "1", "1", "k-fk"), PARENT_KEY_NOT_FOUND);
        assertThat(error.getMessage()).contains("FK_WTX_WALLET");
    }

    @ParameterizedTest(name = "[{index}] {0} / {1} / {2} / {3}")
    @CsvSource(nullValues = "NULL", value = {
            "UPDATE, PKG_WALLET, NULL, CREDIT, CK_BALANCE_AUDIT_SOURCE",
            "UPDATE, PKG_WALLET, 10,   NULL,   CK_BALANCE_AUDIT_SOURCE",
            "UPDATE, DIRECT_SQL, 10,   NULL,   CK_BALANCE_AUDIT_SOURCE",
            "UPDATE, DIRECT_SQL, NULL, DEBIT,  CK_BALANCE_AUDIT_SOURCE",
            "MERGE,  DIRECT_SQL, NULL, NULL,   CK_BALANCE_AUDIT_DML"
    })
    void auditRowMustBeConsistent(String dml, String source, String transactionId, String type, String constraint)
            throws SQLException {
        try (Connection audit = OracleTestDatabase.connect(DbUser.AUDIT)) {
            audit.setAutoCommit(false);
            SQLException error = assertOraError(audit, """
                    INSERT INTO balance_audit (id, wallet_id, dml_operation, balance_before, balance_after,
                                               change_source, transaction_id, transaction_type, db_user)
                    VALUES (seq_balance_audit_id.NEXTVAL, 1, '%s', 0, 10, '%s', %s, %s, USER)"""
                    .formatted(dml, source, transactionId == null ? "NULL" : transactionId,
                            type == null ? "NULL" : "'" + type + "'"), CHECK_VIOLATED);
            assertThat(error.getMessage()).contains(constraint);
            audit.rollback();
        }
    }

    private String insertTransaction(String type, String amount, String balanceAfter, String key) {
        return insertTransaction(walletId, type, amount, balanceAfter, key);
    }

    private String insertTransaction(long wallet, String type, String amount, String balanceAfter, String key) {
        return """
                INSERT INTO wallet_transactions (id, wallet_id, transaction_type, amount, balance_after, idempotency_key)
                VALUES (seq_wallet_transaction_id.NEXTVAL, %d, '%s', %s, %s, '%s')""".formatted(wallet, type, amount, balanceAfter, key);
    }

    private String queryString(String sql) throws SQLException {
        try (Statement st = owner.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private long nextVal(String sequence) throws SQLException {
        try (Statement st = owner.createStatement(); ResultSet rs = st.executeQuery("SELECT " + sequence + ".NEXTVAL FROM dual")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
