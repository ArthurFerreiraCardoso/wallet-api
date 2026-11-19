package com.example.wallet_api.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Oracle Free compartilhado por todos os testes de integracao da JVM.
 * Mesma imagem, mesmo db/init e mesmas migrations (na mesma ordem) do docker-compose.
 */
public final class OracleTestDatabase {

    private static final int ORACLE_PORT = 1521;

    private static final Set<Integer> LISTENER_BUSY_CODES = Set.of(12516, 12519, 12520);

    private static final Duration LISTENER_RETRY_WINDOW = Duration.ofSeconds(30);

    private static final OracleContainer CONTAINER = createContainer();

    static {
        CONTAINER.start();
        migrate(DbUser.AUDIT, "filesystem:db/migration/audit");
        migrate(DbUser.OWNER, "filesystem:db/migration/owner");
    }

    private OracleTestDatabase() {
    }

    public static String jdbcUrl() {
        return "jdbc:oracle:thin:@//%s:%d/FREEPDB1".formatted(CONTAINER.getHost(), CONTAINER.getMappedPort(ORACLE_PORT));
    }

    /**
     * Abre uma conexao dedicada, repetindo quando o listener recusa por excesso de carga estimada.
     * O listener conta as conexoes que abriu desde a ultima atualizacao enviada pela instancia; numa rajada de
     * abre-e-fecha (testes de concorrencia em rodadas) essa estimativa passa do limite com poucas sessoes vivas e ele
     * responde ORA-12516/12519/12520 ate a proxima atualizacao, alguns segundos depois.
     */
    public static Connection connect(DbUser user) throws SQLException {
        long deadline = System.nanoTime() + LISTENER_RETRY_WINDOW.toNanos();
        long backoffMillis = 200;
        while (true) {
            try {
                return DriverManager.getConnection(jdbcUrl(), user.username(), user.password());
            } catch (SQLException e) {
                if (!LISTENER_BUSY_CODES.contains(e.getErrorCode()) || System.nanoTime() > deadline) {
                    throw e;
                }
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, 2000);
            }
        }
    }

    private static void sleep(long millis) throws SQLException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrompido aguardando o listener", e);
        }
    }

    private static void migrate(DbUser user, String location) {
        Flyway.configure()
                .dataSource(jdbcUrl(), user.username(), user.password())
                .locations(location)
                .load()
                .migrate();
    }

    private static OracleContainer createContainer() {
        String image = System.getProperty("oracle-free.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "Propriedade oracle-free.image ausente. Rode os testes de integracao pelo Maven (./mvnw verify).");
        }
        // O OracleContainer tambem cria o usuario TEST (APP_USER da imagem); os testes nao o utilizam.
        OracleContainer container = new OracleContainer(DockerImageName.parse(image))
                .withCopyFileToContainer(MountableFile.forHostPath("db/init", 0755), "/container-entrypoint-initdb.d/")
                .withStartupTimeout(Duration.ofMinutes(5));
        for (DbUser user : DbUser.values()) {
            container.withEnv(user.passwordEnvVar(), user.password());
        }
        return container;
    }
}
