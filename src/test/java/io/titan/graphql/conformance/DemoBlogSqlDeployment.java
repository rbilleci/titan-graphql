package io.titan.graphql.conformance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared test support (extracted from {@code GraphqlSqlModeEquivalenceIT}, W2): deploys the
 * demo DDL, the packaged Titan migrations, and the Java-mode fixture rows onto a live
 * database connection. Used by the W2 equivalence IT (PostgreSQL leg, and the W5.2 MySQL
 * leg) and the W5.1 SQL-mode HTTP serving IT.
 */
public final class DemoBlogSqlDeployment {

    private DemoBlogSqlDeployment() {
    }

    /**
     * Mirror of the Java-mode fixture dataset ({@code DemoBlogFixtureStore}). The transpiled
     * kernel carries its bounded demo data inside the generated routines, but the deployed
     * schema is seeded identically so the database state matches what Java mode assumes.
     */
    public static final String FIXTURE_SEED_SQL = """
            INSERT INTO users (id, name, email, role) VALUES
              (10, 'Ada Lovelace', 'ada@example.test', 'author'),
              (11, 'Grace Hopper', 'grace@example.test', 'author');
            INSERT INTO articles (id, author_id, title, body, published) VALUES
              (1, 10, 'Titan GraphQL proof', 'First proof body', true),
              (2, 11, 'Stored functions as APIs', 'Second proof body', false);
            INSERT INTO comments (id, article_id, author_id, body) VALUES
              (100, 1, 11, 'First comment'),
              (101, 1, 10, 'Second comment'),
              (102, 2, 10, 'API comment');
            """;

    /**
     * Deploys the demo DDL, the packaged runtime migration, the packaged routine bundle, and
     * the fixture seed onto the connected database.
     *
     * <p>Each migration file executes as a single multi-statement JDBC call — the PostgreSQL
     * driver parses dollar-quoted bodies natively, which is exactly how core's
     * {@code titanVerifyInstall} treats the same files. {@code R__titan_010_runtime.sql} is
     * idempotent ({@code CREATE ... IF NOT EXISTS} / {@code CREATE OR REPLACE}), so
     * re-applying the packaged file on a harness-prepared database keeps the deployment
     * identical to a from-scratch install.</p>
     */
    public static void deployPackagedKernel(Connection connection) throws IOException, SQLException {
        executeScript(connection, Path.of("ddl", "postgres", "titan_graphql_postgres.sql"));
        Path migrationsDir = Path.of(System.getProperty(
                "titan.graphql.migrations.dir",
                "build/generated/migrations/titan/postgresql"));
        executeScript(connection, migrationsDir.resolve("R__titan_010_runtime.sql"));
        executeScript(connection, migrationsDir.resolve("R__titan_020_routines.sql"));
        try (Statement statement = connection.createStatement()) {
            statement.execute(FIXTURE_SEED_SQL);
        }
    }

    /**
     * MySQL leg (completion plan W5.2): deploys the MySQL demo DDL
     * ({@code ddl/mysql/titan_graphql_mysql.sql}), the packaged MySQL migrations, and the fixture
     * seed onto a live MySQL connection.
     *
     * <p>Unlike the PostgreSQL leg, the MySQL JDBC driver does not accept multi-statement
     * scripts in one {@code Statement.execute}, and the emitted MySQL routine bundle uses the
     * mysql-client {@code DELIMITER} convention for routine bodies — statements are split
     * here the same way core's harness ({@code SqlScripts}) and {@code titanVerifyInstall}
     * split them. The kernel's objects are qualified with the configured Titan schema
     * ({@code `public`}), which on MySQL is a server-global database; the DDL script is
     * re-runnable (DROP TABLE IF EXISTS) and the routine bundle replaces via
     * {@code DROP FUNCTION IF EXISTS}, so repeated deployments on the shared container stay
     * equivalent to a fresh install.</p>
     */
    public static void deployPackagedKernelMySql(Connection connection) throws IOException, SQLException {
        executeScriptStatementWise(connection, Path.of("ddl", "mysql", "titan_graphql_mysql.sql"));
        Path migrationsDir = Path.of(System.getProperty(
                "titan.graphql.migrations.dir.mysql",
                "build/generated/migrations/titan/mysql"));
        executeScriptStatementWise(connection, migrationsDir.resolve("R__titan_010_runtime.sql"));
        executeScriptStatementWise(connection, migrationsDir.resolve("R__titan_020_routines.sql"));
        // Same fixture dataset as the PostgreSQL leg; MySQL needs the explicit `public`
        // database qualifier because the test connection's default database is the per-test
        // harness database, not `public`.
        String mysqlSeed = FIXTURE_SEED_SQL.replace("INSERT INTO ", "INSERT INTO public.");
        for (String statementSql : splitStatements(mysqlSeed)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }
    }

    private static void executeScript(Connection connection, Path sqlFile) throws IOException, SQLException {
        String script = readScript(sqlFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute(script);
        } catch (SQLException ex) {
            throw new SQLException("failed to deploy " + sqlFile + ": " + ex.getMessage(), ex);
        }
    }

    private static void executeScriptStatementWise(Connection connection, Path sqlFile)
            throws IOException, SQLException {
        for (String statementSql : splitStatements(readScript(sqlFile))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            } catch (SQLException ex) {
                throw new SQLException("failed to deploy " + sqlFile + " statement:\n" + statementSql
                        + "\n-> " + ex.getMessage(), ex);
            }
        }
    }

    private static String readScript(Path sqlFile) throws IOException {
        if (Files.exists(sqlFile) == false) {
            throw new IllegalStateException("missing SQL script (run titanPackage first?): "
                    + sqlFile.toAbsolutePath());
        }
        return Files.readString(sqlFile);
    }

    /**
     * Line-based statement splitting honoring the mysql-client {@code DELIMITER} convention —
     * the same convention core's harness ({@code io.titan.runtime.testing.SqlScripts}) and
     * {@code titanVerifyInstall} apply to the emitted MySQL bundles. Comment-only lines
     * outside routine bodies are kept with their following statement (harmless to MySQL).
     */
    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder buffer = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);
            String current = buffer.toString().trim();
            if (current.endsWith(delimiter)) {
                String statementSql = current.substring(0, current.length() - delimiter.length()).trim();
                if (statementSql.isEmpty() == false && isCommentOnly(statementSql) == false) {
                    statements.add(statementSql);
                }
                buffer.setLength(0);
            }
        }
        String tail = buffer.toString().trim();
        if (tail.isEmpty() == false && isCommentOnly(tail) == false) {
            statements.add(tail);
        }
        return statements;
    }

    private static boolean isCommentOnly(String statementSql) {
        for (String line : statementSql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() == false && trimmed.startsWith("--") == false) {
                return false;
            }
        }
        return true;
    }
}
