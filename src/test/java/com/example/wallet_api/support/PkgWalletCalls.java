package com.example.wallet_api.support;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * Chamadas JDBC ao PKG_WALLET, do mesmo jeito que a aplicacao fara: como WALLET_APP e sem commit implicito.
 */
public final class PkgWalletCalls {

    public record CreatedWallet(long id, BigDecimal balance, Timestamp createdAt) {
    }

    public record RegisteredTransaction(long transactionId, BigDecimal balanceAfter, Timestamp createdAt, boolean replayed) {
    }

    private PkgWalletCalls() {
    }

    public static CreatedWallet createWallet(Connection connection, String holderName) throws SQLException {
        try (CallableStatement call = connection.prepareCall("{call wallet_owner.pkg_wallet.create_wallet(?, ?, ?, ?)}")) {
            call.setString(1, holderName);
            call.registerOutParameter(2, Types.NUMERIC);
            call.registerOutParameter(3, Types.NUMERIC);
            call.registerOutParameter(4, Types.TIMESTAMP);
            call.execute();
            return new CreatedWallet(call.getLong(2), call.getBigDecimal(3), call.getTimestamp(4));
        }
    }

    public static RegisteredTransaction register(Connection connection, long walletId, String type, String amount, String key)
            throws SQLException {
        return register(connection, walletId, type, amount == null ? null : new BigDecimal(amount), key);
    }

    public static RegisteredTransaction register(Connection connection, long walletId, String type, BigDecimal amount, String key)
            throws SQLException {
        try (CallableStatement call = connection.prepareCall(
                "{call wallet_owner.pkg_wallet.register_transaction(?, ?, ?, ?, ?, ?, ?, ?)}")) {
            call.setLong(1, walletId);
            call.setString(2, type);
            call.setBigDecimal(3, amount);
            call.setString(4, key);
            call.registerOutParameter(5, Types.NUMERIC);
            call.registerOutParameter(6, Types.NUMERIC);
            call.registerOutParameter(7, Types.TIMESTAMP);
            call.registerOutParameter(8, Types.VARCHAR);
            call.execute();
            return new RegisteredTransaction(call.getLong(5), call.getBigDecimal(6), call.getTimestamp(7), "Y".equals(call.getString(8)));
        }
    }

    /** Conexao do WALLET_APP com autocommit desligado, como na aplicacao. */
    public static Connection appConnection() throws SQLException {
        Connection connection = OracleTestDatabase.connect(DbUser.APP);
        connection.setAutoCommit(false);
        return connection;
    }
}
