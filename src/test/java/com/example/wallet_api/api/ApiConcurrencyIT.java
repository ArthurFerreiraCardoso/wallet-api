package com.example.wallet_api.api;

import static com.example.wallet_api.support.PkgWalletCalls.appConnection;
import static com.example.wallet_api.support.PkgWalletCalls.register;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.wallet_api.support.ApiKeyTestSupport;
import com.example.wallet_api.support.DbUser;
import com.example.wallet_api.support.OracleIntegrationTest;
import com.example.wallet_api.support.OracleTestDatabase;
import com.example.wallet_api.support.PkgWalletCalls.RegisteredTransaction;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Concorrencia de ponta a ponta: clientes HTTP de verdade disparando ao mesmo tempo contra o servidor real,
 * na mesma carteira, com a mesma chave repetida em paralelo e debitos alem do saldo.
 *
 * <p>O cliente faz o que um cliente correto faria: diante de 503 (WALLET_LOCKED ou SERVICE_BUSY), repete a mesma
 * requisicao com a mesma chave. Ao final, cada resposta HTTP, o saldo, o extrato completo e a trilha de auditoria
 * precisam contar exatamente a mesma historia.
 */
class ApiConcurrencyIT extends OracleIntegrationTest {

    private static final int THREADS = 16;
    private static final int MAX_ATTEMPTS = 10;
    private static final BigDecimal INITIAL = new BigDecimal("100.00");
    private static final String RETRIED_KEY = "mesma-chave";
    private static final int RETRIES = 12;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private record Operation(String type, BigDecimal amount, String key) {
    }

    private record Outcome(Operation operation, int status, JsonNode body, boolean replayedHeader) {
    }

    @LocalServerPort
    int port;

    // BigDecimal e nao double: 150.50 precisa ser comparado exatamente.
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private long walletId;

    @AfterAll
    static void closeClient() {
        HTTP.close();
    }

    @BeforeEach
    void createWalletWithInitialBalance() throws Exception {
        HttpResponse<String> created = send("POST", "/wallets", null, "{\"holderName\":\"Concorrencia HTTP\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        walletId = json.readTree(created.body()).get("id").asLong();

        HttpResponse<String> initial = send("POST", "/wallets/" + walletId + "/transactions", "inicial",
                "{\"type\":\"CREDIT\",\"amount\":" + INITIAL + "}");
        assertThat(initial.statusCode()).isEqualTo(201);
    }

    @RepeatedTest(value = 3, name = "rodada {currentRepetition} de {totalRepetitions}")
    void concurrentHttpOperationsOnTheSameWalletKeepTheInvariants() throws Exception {
        // A chave repetida e um CREDITO (nunca recusado por saldo) intercalado na lista: as 12 copias disputam
        // a carteira junto com o resto, e exatamente uma delas tem de ser aplicada.
        List<Operation> operations = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            operations.add(new Operation("CREDIT", new BigDecimal("10.00"), "credito-" + i));
            operations.add(new Operation("DEBIT", new BigDecimal("25.00"), "debito-" + i));
            if (i % 2 == 0 && i / 2 < RETRIES) {
                operations.add(new Operation("CREDIT", new BigDecimal("5.00"), RETRIED_KEY));
            }
        }
        assertThat(operations).filteredOn(o -> o.key().equals(RETRIED_KEY)).hasSize(RETRIES);

        List<Outcome> outcomes = runConcurrently(operations);
        List<Outcome> unique = outcomes.stream().filter(o -> !o.operation().key().equals(RETRIED_KEY)).toList();
        List<Outcome> retried = outcomes.stream().filter(o -> o.operation().key().equals(RETRIED_KEY)).toList();

        // 1. Chave unica nunca e replay: credito sempre 201; debito 201 ou 422 por saldo insuficiente.
        assertThat(unique).allSatisfy(o -> {
            if (o.operation().type().equals("CREDIT")) {
                assertThat(o.status()).as(o.body().toString()).isEqualTo(201);
            } else {
                assertThat(o.status()).as(o.body().toString()).isIn(201, 422);
            }
        });
        // Disponivel no maximo 100 + 250 + 5 = 355; os debitos somam 625: alguns precisam ser recusados.
        assertThat(unique).filteredOn(o -> o.status() == 422).isNotEmpty()
                .allSatisfy(o -> assertThat(o.body().get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS"));

        // 2. A chave repetida 12 vezes em paralelo: um unico 201, onze 200, todos com o mesmo corpo.
        assertThat(retried).extracting(Outcome::status).containsOnly(200, 201);
        assertThat(retried).filteredOn(o -> o.status() == 201).hasSize(1);
        assertThat(retried.stream().map(Outcome::body).distinct()).as("replay devolve o corpo original").hasSize(1);

        // 3. Header de replay presente se, e somente se, a resposta e um replay.
        assertThat(outcomes).allSatisfy(o -> assertThat(o.replayedHeader()).as(o.operation().key()).isEqualTo(o.status() == 200));

        // 4. Saldo final = inicial + creditos aplicados - debitos aplicados (aplicado = 201).
        BigDecimal expected = INITIAL;
        for (Outcome o : outcomes) {
            if (o.status() == 201) {
                expected = o.operation().type().equals("CREDIT")
                        ? expected.add(o.operation().amount())
                        : expected.subtract(o.operation().amount());
            }
        }
        BigDecimal balance = currentBalance();
        assertThat(balance).as("saldo final consistente").isEqualByComparingTo(expected);

        // 5. Extrato completo refeito lancamento a lancamento: chega no mesmo saldo e nunca fica negativo.
        List<JsonNode> statement = fullStatementInIdOrder();
        long applied = outcomes.stream().filter(o -> o.status() == 201).count();
        assertThat(statement).as("um lancamento por operacao aplicada, mais o inicial").hasSize((int) applied + 1);
        BigDecimal running = BigDecimal.ZERO;
        for (JsonNode entry : statement) {
            running = signed(entry).add(running);
            assertThat(entry.get("balanceAfter").decimalValue()).isEqualByComparingTo(running);
            assertThat(running.signum()).as("saldo nunca negativo").isGreaterThanOrEqualTo(0);
        }
        assertThat(running).isEqualByComparingTo(balance);

        // 6. Toda resposta 200/201 e exatamente o lancamento gravado (id, saldo apos, data...).
        Map<Long, JsonNode> byId = statement.stream().collect(Collectors.toMap(e -> e.get("id").asLong(), Function.identity()));
        assertThat(outcomes).filteredOn(o -> o.status() != 422).allSatisfy(o ->
                assertThat(o.body()).as("resposta igual ao extrato").isEqualTo(byId.get(o.body().get("id").asLong())));

        // 7. Auditoria (lida direto no WALLET_AUDIT) conta a mesma historia, linha a linha.
        assertAuditMatches(statement);

        // 8. Retry tardio de um debito aplicado com o saldo zerado: continua replay (200, mesmo corpo), nunca 422.
        //    Pega um package que olhasse o saldo antes de procurar a chave.
        Outcome appliedDebit = unique.stream().filter(o -> o.status() == 201 && o.operation().type().equals("DEBIT"))
                .findFirst().orElseThrow();
        if (balance.signum() > 0) {
            Outcome drain = sendWithRetry(new Operation("DEBIT", balance, "zera-saldo"));
            assertThat(drain.status()).isEqualTo(201);
        }
        Outcome lateRetry = sendWithRetry(appliedDebit.operation());
        assertThat(lateRetry.status()).as(lateRetry.body().toString()).isEqualTo(200);
        assertThat(lateRetry.body()).isEqualTo(appliedDebit.body());
        assertThat(currentBalance()).isEqualByComparingTo("0");
    }

    /**
     * Caminho de contencao, de forma deterministica: uma sessao segura o lock da carteira; a requisicao HTTP com a
     * mesma operacao espera 5 s e recebe 503 WALLET_LOCKED. Depois do commit, o retry com a mesma chave e replay.
     */
    @Test
    void lockedWalletAnswers503AndTheRetryWithTheSameKeyIsAReplay() throws Exception {
        String body = "{\"type\":\"CREDIT\",\"amount\":7.00}";
        RegisteredTransaction held;
        try (Connection holder = appConnection()) {
            held = register(holder, walletId, "CREDIT", "7.00", "segura-lock");

            HttpResponse<String> locked = send("POST", "/wallets/" + walletId + "/transactions", "segura-lock", body);
            assertThat(locked.statusCode()).isEqualTo(503);
            assertThat(locked.headers().firstValue("Retry-After")).hasValue("1");
            assertThat(json.readTree(locked.body()).get("code").asString()).isEqualTo("WALLET_LOCKED");

            holder.commit();
        }

        HttpResponse<String> retry = send("POST", "/wallets/" + walletId + "/transactions", "segura-lock", body);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json.readTree(retry.body()).get("id").asLong()).isEqualTo(held.transactionId());
        assertThat(currentBalance()).isEqualByComparingTo(INITIAL.add(new BigDecimal("7.00")));
    }

    private List<Outcome> runConcurrently(List<Operation> operations) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Operation op : operations) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return sendWithRetry(op);
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(3, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Repete com a mesma chave enquanto a resposta for 503, respeitando o Retry-After (com um pouco de jitter). */
    private Outcome sendWithRetry(Operation op) throws Exception {
        String body = "{\"type\":\"%s\",\"amount\":%s}".formatted(op.type(), op.amount());
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response = send("POST", "/wallets/" + walletId + "/transactions", op.key(), body);
            if (response.statusCode() != 503) {
                break;
            }
            long retryAfterMillis = response.headers().firstValueAsLong("Retry-After").orElse(1) * 1000;
            TimeUnit.MILLISECONDS.sleep(retryAfterMillis + ThreadLocalRandom.current().nextLong(250));
        }
        return new Outcome(op, response.statusCode(), json.readTree(response.body()),
                response.headers().firstValue("Idempotent-Replayed").map("true"::equals).orElse(false));
    }

    private BigDecimal currentBalance() throws Exception {
        return json.readTree(send("GET", "/wallets/" + walletId, null, null).body()).get("balance").decimalValue();
    }

    private List<JsonNode> fullStatementInIdOrder() throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        int page = 0;
        int totalPages;
        do {
            // size=20 de proposito: o extrato tem dezenas de lancamentos e a paginacao tambem e exercitada.
            JsonNode body = json.readTree(send("GET", "/wallets/" + walletId + "/transactions?size=20&page=" + page, null, null).body());
            body.get("content").forEach(entries::add);
            totalPages = body.get("totalPages").asInt();
            page++;
        } while (page < totalPages);
        entries.sort((a, b) -> Long.compare(a.get("id").asLong(), b.get("id").asLong()));
        return entries;
    }

    private static BigDecimal signed(JsonNode entry) {
        BigDecimal amount = entry.get("amount").decimalValue();
        return entry.get("type").asString().equals("CREDIT") ? amount : amount.negate();
    }

    private void assertAuditMatches(List<JsonNode> statement) throws SQLException {
        List<Map<String, Object>> audit = new ArrayList<>();
        try (Connection connection = OracleTestDatabase.connect(DbUser.AUDIT);
             PreparedStatement ps = connection.prepareStatement("""
                     SELECT change_source, transaction_id, transaction_type, balance_before, balance_after
                       FROM balance_audit WHERE wallet_id = ? ORDER BY id""")) {
            ps.setLong(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    audit.add(Map.of("source", rs.getString(1), "txId", rs.getLong(2), "type", rs.getString(3),
                            "before", rs.getBigDecimal(4), "after", rs.getBigDecimal(5)));
                }
            }
        }

        assertThat(audit).as("uma linha de auditoria por lancamento").hasSize(statement.size());
        BigDecimal previous = BigDecimal.ZERO;
        for (int i = 0; i < statement.size(); i++) {
            JsonNode entry = statement.get(i);
            Map<String, Object> row = audit.get(i);
            assertThat(row.get("source")).isEqualTo("PKG_WALLET");
            assertThat(row.get("txId")).isEqualTo(entry.get("id").asLong());
            assertThat(row.get("type")).isEqualTo(entry.get("type").asString());
            assertThat((BigDecimal) row.get("before")).as("comeca onde a anterior terminou").isEqualByComparingTo(previous);
            assertThat((BigDecimal) row.get("after")).isEqualByComparingTo(entry.get("balanceAfter").decimalValue());
            previous = (BigDecimal) row.get("after");
        }
    }

    private HttpResponse<String> send(String method, String path, String idempotencyKey, String jsonBody) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-API-Key", ApiKeyTestSupport.API_KEY)
                .header("Content-Type", "application/json");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        request.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
