package com.example.wallet_api.infrastructure.oracle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.PageResult;
import com.example.wallet_api.domain.Period;
import com.example.wallet_api.domain.TransactionType;
import com.example.wallet_api.domain.Wallet;
import com.example.wallet_api.domain.WalletTransaction;
import com.example.wallet_api.domain.port.WalletQueryPort;

/**
 * Le as views V_WALLETS e V_WALLET_TRANSACTIONS, unico acesso de leitura do WALLET_APP.
 * As colunas de data sao TIMESTAMP sem fuso gravadas em UTC; a conversao para Instant e explicita.
 */
@Repository
class OracleWalletQueryAdapter implements WalletQueryPort {

    private static final String TRANSACTION_COLUMNS =
            "id, wallet_id, transaction_type, amount, balance_after, idempotency_key, created_at";

    private final JdbcClient jdbcClient;

    OracleWalletQueryAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Wallet> findWallet(long walletId) {
        return jdbcClient.sql("""
                        SELECT id, holder_name, balance, created_at, updated_at
                          FROM wallet_owner.v_wallets
                         WHERE id = :id""")
                .param("id", walletId)
                .query(OracleWalletQueryAdapter::mapWallet)
                .optional();
    }

    @Override
    public PageResult<WalletTransaction> findTransactions(long walletId, Period period, PageRequest pageRequest) {
        StringBuilder where = new StringBuilder(" WHERE wallet_id = :walletId");
        if (period.from() != null) {
            where.append(" AND created_at >= :from");
        }
        if (period.to() != null) {
            where.append(" AND created_at < :to");
        }

        long total = bindPeriod(jdbcClient.sql("SELECT COUNT(*) FROM wallet_owner.v_wallet_transactions" + where), walletId, period)
                .query(Long.class)
                .single();

        List<WalletTransaction> content = bindPeriod(jdbcClient.sql(
                        "SELECT " + TRANSACTION_COLUMNS + " FROM wallet_owner.v_wallet_transactions" + where
                                + " ORDER BY created_at DESC, id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY"),
                walletId, period)
                .param("offset", pageRequest.offset())
                .param("size", pageRequest.size())
                .query(OracleWalletQueryAdapter::mapTransaction)
                .list();

        return new PageResult<>(content, pageRequest.page(), pageRequest.size(), total);
    }

    private static JdbcClient.StatementSpec bindPeriod(JdbcClient.StatementSpec spec, long walletId, Period period) {
        spec = spec.param("walletId", walletId);
        if (period.from() != null) {
            spec = spec.param("from", toUtcDateTime(period.from()));
        }
        if (period.to() != null) {
            spec = spec.param("to", toUtcDateTime(period.to()));
        }
        return spec;
    }

    private static Wallet mapWallet(ResultSet rs, int rowNum) throws SQLException {
        return new Wallet(
                rs.getLong("id"),
                rs.getString("holder_name"),
                money(rs.getBigDecimal("balance")),
                toInstant(rs.getObject("created_at", LocalDateTime.class)),
                toInstant(rs.getObject("updated_at", LocalDateTime.class)));
    }

    private static WalletTransaction mapTransaction(ResultSet rs, int rowNum) throws SQLException {
        return new WalletTransaction(
                rs.getLong("id"),
                rs.getLong("wallet_id"),
                TransactionType.valueOf(rs.getString("transaction_type")),
                money(rs.getBigDecimal("amount")),
                money(rs.getBigDecimal("balance_after")),
                rs.getString("idempotency_key"),
                toInstant(rs.getObject("created_at", LocalDateTime.class)));
    }

    /** O banco devolve 100 para 100.00; a API sempre expoe 2 casas. */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toUtcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
