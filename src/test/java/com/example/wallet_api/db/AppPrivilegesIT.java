package com.example.wallet_api.db;

import static com.example.wallet_api.support.SqlAssertions.assertOraError;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.wallet_api.support.DbUser;
import com.example.wallet_api.support.OracleTestDatabase;

/**
 * Prova o menor privilegio do WALLET_APP: le apenas as views, nao escreve em nada e nao enxerga a auditoria.
 */
class AppPrivilegesIT {

    private Connection app;

    @BeforeEach
    void connect() throws SQLException {
        app = OracleTestDatabase.connect(DbUser.APP);
        app.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        app.rollback();
        app.close();
    }

    @Test
    void objectGrantsAreExactlyReadOnTheViews() throws SQLException {
        Set<String> grants = new HashSet<>();
        try (Statement st = app.createStatement();
             ResultSet rs = st.executeQuery("SELECT owner || '.' || table_name || ':' || privilege FROM user_tab_privs WHERE grantee = 'WALLET_APP'")) {
            while (rs.next()) {
                grants.add(rs.getString(1));
            }
        }

        assertThat(grants).containsExactlyInAnyOrder(
                "WALLET_OWNER.V_WALLETS:READ",
                "WALLET_OWNER.V_WALLET_TRANSACTIONS:READ");
    }

    @Test
    void schemaOwnersGrantNothingToPublicAndAuditGrantsNothingToTheApp() throws SQLException {
        for (DbUser grantor : new DbUser[] { DbUser.OWNER, DbUser.AUDIT }) {
            Set<String> unexpected = new HashSet<>();
            try (Connection connection = OracleTestDatabase.connect(grantor);
                 Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT grantor || '->' || grantee || ':' || table_name || ':' || privilege FROM user_tab_privs_made
                          WHERE grantee = 'PUBLIC' OR (grantee = 'WALLET_APP' AND grantor = 'WALLET_AUDIT')""")) {
                while (rs.next()) {
                    unexpected.add(rs.getString(1));
                }
            }
            assertThat(unexpected).as("grants indevidos feitos por %s", grantor.username()).isEmpty();
        }
    }

    @Test
    void canReadTheViews() throws SQLException {
        try (Statement st = app.createStatement()) {
            st.executeQuery("SELECT COUNT(*) FROM wallet_owner.v_wallets").close();
            st.executeQuery("SELECT COUNT(*) FROM wallet_owner.v_wallet_transactions").close();
        }
    }

    @ParameterizedTest(name = "[{index}] ORA-{1}: {0}")
    @CsvSource(delimiter = '|', value = {
            "SELECT * FROM wallet_owner.wallets                                                    | 942",
            "SELECT * FROM wallet_owner.wallet_transactions                                        | 942",
            "INSERT INTO wallet_owner.wallets (id) VALUES (-1)                                     | 942",
            "UPDATE wallet_owner.wallets SET balance = 1000000                                     | 942",
            "DELETE FROM wallet_owner.wallet_transactions                                          | 942",
            "INSERT INTO wallet_owner.v_wallets (id) VALUES (-1)                                   | 41900",
            "UPDATE wallet_owner.v_wallets SET balance = 1000000                                   | 41900",
            "DELETE FROM wallet_owner.v_wallet_transactions                                        | 41900",
            "SELECT * FROM wallet_owner.v_wallets FOR UPDATE                                       | 41900",
            "LOCK TABLE wallet_owner.v_wallets IN EXCLUSIVE MODE                                   | 41900",
            "SELECT wallet_owner.seq_wallet_id.NEXTVAL FROM dual                                   | 942",
            "SELECT * FROM wallet_owner.flyway_schema_history                                      | 942",
            "SELECT * FROM wallet_audit.balance_audit                                              | 942",
            "DELETE FROM wallet_audit.balance_audit                                                | 942",
            "SELECT wallet_audit.seq_balance_audit_id.NEXTVAL FROM dual                            | 942",
            "SELECT * FROM wallet_audit.flyway_schema_history                                      | 942",
            "CREATE TABLE app_owned (id NUMBER)                                                    | 1031"
    })
    void cannotWriteOrReachAnythingElse(String sql, int oraCode) {
        assertOraError(app, sql.strip(), oraCode);
    }
}
