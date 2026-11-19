package com.example.wallet_api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.wallet_api.support.DbUser;
import com.example.wallet_api.support.OracleTestDatabase;

/**
 * Prova que o script de init cria os tres usuarios com o minimo de privilegios de sistema.
 */
class DatabaseUsersIT {

    static Stream<Arguments> expectedSystemPrivileges() {
        return Stream.of(
                Arguments.of(DbUser.APP, List.of("CREATE SESSION")),
                Arguments.of(DbUser.OWNER, List.of("CREATE SESSION", "CREATE TABLE", "CREATE SEQUENCE",
                        "CREATE PROCEDURE", "CREATE TRIGGER", "CREATE VIEW")),
                Arguments.of(DbUser.AUDIT, List.of("CREATE SESSION", "CREATE TABLE", "CREATE SEQUENCE",
                        "CREATE PROCEDURE", "CREATE TRIGGER")));
    }

    static Stream<Arguments> expectedQuota() {
        return Stream.of(
                Arguments.of(DbUser.OWNER, -1L),
                Arguments.of(DbUser.AUDIT, -1L),
                Arguments.of(DbUser.APP, 0L));
    }

    @ParameterizedTest
    @MethodSource("expectedSystemPrivileges")
    void userHasExactlyTheExpectedSystemPrivilegesAndNoRoles(DbUser user, List<String> expected) throws SQLException {
        try (Connection connection = OracleTestDatabase.connect(user)) {
            assertThat(querySet(connection, "SELECT privilege FROM user_sys_privs")).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(querySet(connection, "SELECT granted_role FROM user_role_privs")).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("expectedQuota")
    void onlyObjectOwnersHaveQuota(DbUser user, long expectedMaxBytes) throws SQLException {
        try (Connection connection = OracleTestDatabase.connect(user);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT NVL(MAX(max_bytes), 0) FROM user_ts_quotas WHERE tablespace_name = 'USERS'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(expectedMaxBytes);
        }
    }

    private static Set<String> querySet(Connection connection, String sql) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }
}
