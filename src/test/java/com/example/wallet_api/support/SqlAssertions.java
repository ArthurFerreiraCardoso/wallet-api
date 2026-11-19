package com.example.wallet_api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqlAssertions {

    private SqlAssertions() {
    }

    /**
     * Executa o SQL e exige que falhe com o codigo ORA informado (ex.: 942 para ORA-00942).
     * Devolve a excecao para asserts adicionais sobre a mensagem.
     */
    public static SQLException assertOraError(Connection connection, String sql, int oraCode) {
        SQLException error = catchThrowableOfType(SQLException.class, () -> execute(connection, sql));
        assertThat((Throwable) error).as("esperava ORA-%05d para: %s", oraCode, sql).isNotNull();
        assertThat(error.getErrorCode()).as("codigo ORA para: %s (%s)", sql, error.getMessage()).isEqualTo(oraCode);
        return error;
    }

    public static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
