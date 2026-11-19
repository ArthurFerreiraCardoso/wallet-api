package com.example.wallet_api.infrastructure.oracle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.wallet_api.domain.RegisteredTransaction;
import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.WalletException;
import com.example.wallet_api.domain.WalletTransaction;
import com.example.wallet_api.domain.port.WalletCommandPort;

/**
 * Chama as procedures do PKG_WALLET. Unico caminho de escrita da aplicacao: o WALLET_APP nao tem DML nas tabelas.
 * Deve rodar dentro de uma transacao do chamador (o package nao comita).
 */
@Repository
class PkgWalletAdapter implements WalletCommandPort {

    private static final String CREATE_WALLET = "{call wallet_owner.pkg_wallet.create_wallet(?, ?, ?, ?)}";
    private static final String REGISTER_TRANSACTION =
            "{call wallet_owner.pkg_wallet.register_transaction(?, ?, ?, ?, ?, ?, ?, ?)}";

    private final JdbcTemplate jdbcTemplate;
    private final OracleErrorTranslator errorTranslator;

    PkgWalletAdapter(JdbcTemplate jdbcTemplate, OracleErrorTranslator errorTranslator) {
        this.jdbcTemplate = jdbcTemplate;
        this.errorTranslator = errorTranslator;
    }

    @Override
    public long createWallet(String holderName) {
        return call(CREATE_WALLET, (CallableStatementCallback<Long>) cs -> {
            cs.setString(1, holderName);
            cs.registerOutParameter(2, Types.NUMERIC);
            cs.registerOutParameter(3, Types.NUMERIC);
            cs.registerOutParameter(4, Types.TIMESTAMP);
            cs.execute();
            return cs.getLong(2);
        });
    }

    @Override
    public RegisteredTransaction registerTransaction(long walletId, String type, BigDecimal amount, String idempotencyKey) {
        return call(REGISTER_TRANSACTION, (CallableStatementCallback<RegisteredTransaction>) cs -> {
            cs.setLong(1, walletId);
            cs.setString(2, type);
            cs.setBigDecimal(3, amount);
            cs.setString(4, idempotencyKey);
            cs.registerOutParameter(5, Types.NUMERIC);
            cs.registerOutParameter(6, Types.NUMERIC);
            cs.registerOutParameter(7, Types.TIMESTAMP);
            cs.registerOutParameter(8, Types.VARCHAR);
            cs.execute();
            return toResult(cs, walletId, type, amount, idempotencyKey);
        });
    }

    /** So chega aqui se o package aceitou: tipo e valor ja foram validados por ele (valor com no maximo 2 casas). */
    private static RegisteredTransaction toResult(CallableStatement cs, long walletId, String type, BigDecimal amount,
                                                  String idempotencyKey) throws SQLException {
        // getObject(LocalDateTime) evita passar pelo fuso da JVM, igual a leitura das views.
        LocalDateTime createdAt = cs.getObject(7, LocalDateTime.class);
        WalletTransaction transaction = new WalletTransaction(
                cs.getLong(5),
                walletId,
                TransactionType.valueOf(type),
                amount.setScale(2, RoundingMode.UNNECESSARY),
                cs.getBigDecimal(6).setScale(2, RoundingMode.UNNECESSARY),
                idempotencyKey,
                createdAt.toInstant(ZoneOffset.UTC));
        return new RegisteredTransaction(transaction, "Y".equals(cs.getString(8)));
    }

    private <T> T call(String sql, CallableStatementCallback<T> callback) {
        try {
            return jdbcTemplate.execute(sql, callback);
        } catch (DataAccessException e) {
            Optional<WalletException> businessError = errorTranslator.translate(e);
            if (businessError.isPresent()) {
                throw businessError.get();
            }
            throw e;
        }
    }
}
