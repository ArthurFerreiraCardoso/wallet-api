package com.example.wallet_api.db;

import static com.example.wallet_api.support.PkgWalletCalls.appConnection;
import static com.example.wallet_api.support.PkgWalletCalls.createWallet;
import static com.example.wallet_api.support.PkgWalletCalls.register;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;

import com.example.wallet_api.support.DbUser;
import com.example.wallet_api.support.OracleTestDatabase;
import com.example.wallet_api.support.PkgWalletCalls.RegisteredTransaction;

/**
 * Muitas sessoes disparando operacoes na mesma carteira ao mesmo tempo, direto no PKG_WALLET.
 * Prova no banco: saldo nunca negativo, nada aplicado em dobro e saldo final igual a soma do que foi aplicado.
 */
class PkgWalletConcurrencyIT {

    private static final int THREADS = 16;
    private static final BigDecimal INITIAL = new BigDecimal("100.00");
    private static final String DUPLICATED_KEY = "same-key";

    private record Operation(String type, BigDecimal amount, String key) {
    }

    /** errorCode 0 = aplicado ou replay. */
    private record Outcome(Operation operation, RegisteredTransaction result, int errorCode) {
    }

    @RepeatedTest(value = 5, name = "rodada {currentRepetition} de {totalRepetitions}")
    void concurrentOperationsOnTheSameWalletKeepTheInvariants() throws Exception {
        long walletId;
        try (Connection setup = appConnection()) {
            walletId = createWallet(setup, "concorrencia").id();
            register(setup, walletId, "CREDIT", INITIAL, "initial");
            setup.commit();
        }

        List<Operation> operations = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            operations.add(new Operation("CREDIT", new BigDecimal("10.00"), "credit-" + i));
            operations.add(new Operation("DEBIT", new BigDecimal("20.00"), "debit-" + i));
        }
        // A mesma operacao chegando 10 vezes ao mesmo tempo (retries do cliente).
        for (int i = 0; i < 10; i++) {
            operations.add(new Operation("CREDIT", new BigDecimal("5.00"), DUPLICATED_KEY));
        }

        List<Outcome> outcomes = runConcurrently(walletId, operations);

        assertThat(outcomes).as("so pode haver sucesso ou saldo insuficiente")
                .allSatisfy(o -> assertThat(o.errorCode()).isIn(0, 20004));
        // Disponivel no maximo 100 + 300 + 5 = 405; os debitos somam 600, entao alguns tem de ser recusados.
        assertThat(outcomes).filteredOn(o -> o.errorCode() == 20004)
                .as("apenas debitos sao recusados por saldo, e alguns precisam ser")
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.operation().type()).isEqualTo("DEBIT"));

        List<Outcome> duplicated = outcomes.stream().filter(o -> o.operation().key().equals(DUPLICATED_KEY)).toList();
        assertThat(duplicated).filteredOn(o -> !o.result().replayed()).as("a chave repetida e aplicada uma unica vez").hasSize(1);
        assertThat(duplicated).extracting(o -> o.result().transactionId()).as("todos os retries devolvem o mesmo lancamento")
                .containsOnly(duplicated.get(0).result().transactionId());

        List<Outcome> applied = outcomes.stream().filter(o -> o.errorCode() == 0 && !o.result().replayed()).toList();
        BigDecimal expectedBalance = INITIAL;
        for (Outcome o : applied) {
            expectedBalance = o.operation().type().equals("CREDIT")
                    ? expectedBalance.add(o.operation().amount())
                    : expectedBalance.subtract(o.operation().amount());
        }

        try (Connection check = appConnection()) {
            assertThat(balanceOf(check, walletId)).as("saldo final = inicial + creditos aplicados - debitos aplicados")
                    .isEqualByComparingTo(expectedBalance);

            List<BigDecimal[]> ledger = ledgerInIdOrder(check, walletId);
            assertThat(ledger).as("um lancamento por operacao aplicada, mais o credito inicial").hasSize(applied.size() + 1);

            // Refaz o saldo lancamento a lancamento na ordem dos ids: se duas operacoes tivessem lido o mesmo saldo,
            // algum balance_after nao bateria com o acumulado.
            BigDecimal running = BigDecimal.ZERO;
            for (BigDecimal[] entry : ledger) {
                running = running.add(entry[0]);
                assertThat(entry[1]).as("balance_after coerente com a sequencia").isEqualByComparingTo(running);
                assertThat(running.signum()).as("saldo nunca negativo").isGreaterThanOrEqualTo(0);
            }
            assertThat(running).isEqualByComparingTo(expectedBalance);
        }

        // A trilha de auditoria (lida como WALLET_AUDIT) tem que contar a mesma historia que o extrato.
        List<Long> ledgerIds;
        try (Connection check = appConnection()) {
            ledgerIds = ledgerIdsInOrder(check, walletId);
        }
        try (Connection audit = OracleTestDatabase.connect(DbUser.AUDIT)) {
            List<AuditEntry> trail = auditTrail(audit, walletId);
            assertThat(trail).as("toda alteracao veio do package").extracting(AuditEntry::source).containsOnly("PKG_WALLET");
            assertThat(trail).as("cada linha aponta para o lancamento correspondente, na mesma ordem")
                    .extracting(AuditEntry::transactionId).containsExactlyElementsOf(ledgerIds);
            BigDecimal previous = BigDecimal.ZERO;
            for (AuditEntry row : trail) {
                assertThat(row.before()).as("cada linha comeca onde a anterior terminou").isEqualByComparingTo(previous);
                previous = row.after();
            }
            assertThat(previous).as("ultima linha = saldo final").isEqualByComparingTo(expectedBalance);
        }
    }

    private record AuditEntry(String source, Long transactionId, BigDecimal before, BigDecimal after) {
    }

    private static List<AuditEntry> auditTrail(Connection audit, long walletId) throws SQLException {
        List<AuditEntry> trail = new ArrayList<>();
        try (PreparedStatement ps = audit.prepareStatement("""
                SELECT change_source, transaction_id, balance_before, balance_after
                  FROM balance_audit WHERE wallet_id = ? ORDER BY id""")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    trail.add(new AuditEntry(rs.getString(1), rs.getObject(2, Long.class), rs.getBigDecimal(3), rs.getBigDecimal(4)));
                }
            }
        }
        return trail;
    }

    private static List<Long> ledgerIdsInOrder(Connection connection, long walletId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM wallet_owner.v_wallet_transactions WHERE wallet_id = ? ORDER BY id")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /**
     * Uma conexao por thread, reaproveitada entre operacoes (como um pool de conexoes faria na aplicacao).
     * Todas as threads esperam o mesmo sinal de largada para disputar o lock juntas.
     */
    private List<Outcome> runConcurrently(long walletId, List<Operation> operations) throws Exception {
        BlockingQueue<Connection> connections = new ArrayBlockingQueue<>(THREADS);
        for (int i = 0; i < THREADS; i++) {
            connections.add(appConnection());
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Operation op : operations) {
                futures.add(pool.submit(() -> {
                    start.await();
                    Connection connection = connections.take();
                    try {
                        RegisteredTransaction result = register(connection, walletId, op.type(), op.amount(), op.key());
                        connection.commit();
                        return new Outcome(op, result, 0);
                    } catch (SQLException e) {
                        connection.rollback();
                        return new Outcome(op, null, e.getErrorCode());
                    } finally {
                        connections.put(connection);
                    }
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }

    private static BigDecimal balanceOf(Connection connection, long walletId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM wallet_owner.v_wallets WHERE id = ?")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    /** Cada item: [delta com sinal, balance_after]. */
    private static List<BigDecimal[]> ledgerInIdOrder(Connection connection, long walletId) throws SQLException {
        List<BigDecimal[]> ledger = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT CASE transaction_type WHEN 'CREDIT' THEN amount ELSE -amount END, balance_after
                  FROM wallet_owner.v_wallet_transactions
                 WHERE wallet_id = ?
                 ORDER BY id""")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ledger.add(new BigDecimal[] { rs.getBigDecimal(1), rs.getBigDecimal(2) });
                }
            }
        }
        return ledger;
    }
}
