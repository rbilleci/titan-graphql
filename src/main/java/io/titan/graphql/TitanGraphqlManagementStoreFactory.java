package io.titan.graphql;

import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller.Dialect;
import io.titan.management.ManagementTransactions.TransactionalMutationStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Selects the management store ({@code /admin/graphql} mutation plane) for the dogfood campaign
 * Phase C: {@code titan.graphql.management.store} = {@code file} (default — Docker-free, keeps plain
 * {@code test} and dev green) | {@code jdbc} (the durable, dogfooded core JDBC store).
 *
 * <p><b>file</b> (default) builds {@link TitanGraphqlDurableManagementStore} over core's file-backed
 * {@code FileTransactionalMutationStore} when a transaction-log path is configured
 * ({@code titan.graphql.management.transactionLog} / {@code TITAN_GRAPHQL_MANAGEMENT_TRANSACTION_LOG}),
 * else the in-memory store — exactly the pre-Phase-C behaviour, unchanged.
 *
 * <p><b>jdbc</b> builds {@link TitanGraphqlDurableManagementStore} over core's
 * {@link JdbcTransactionalMutationStore} (mutations run on Titan-transpiled routines, proven durable
 * + concurrent by core's {@code JdbcManagementStoreDogfoodIT} on PG 16 + MySQL 8.4). The DataSource is
 * the Quarkus default datasource (Agroal, {@code quarkus.datasource.*}) — the SAME W5a datasource the
 * SQL execution mode serves from. There is NO silent fallback to file: a missing/unreachable datasource
 * fails fast and descriptively, telling the operator to configure {@code quarkus.datasource} or switch
 * the mode back to file.
 *
 * <p>Runtime jdbc mode requires an installed management schema and routines. Scratch-test callers
 * use {@link #jdbcStore(DataSource, Dialect)} to install the bundle explicitly.
 */
final class TitanGraphqlManagementStoreFactory {

    static final String STORE_PROPERTY = "titan.graphql.management.store";
    static final String STORE_ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_MANAGEMENT_STORE";
    static final String TRANSACTION_LOG_PROPERTY = "titan.graphql.management.transactionLog";
    static final String TRANSACTION_LOG_ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_MANAGEMENT_TRANSACTION_LOG";
    /** Optional explicit dialect override; otherwise derived from the datasource / db-kind. */
    static final String DIALECT_PROPERTY = "titan.graphql.management.dialect";

    /** The schema the bundled management DDL + routines live in (matches core's {@code ManagementJdbc.SCHEMA}). */
    static final String MANAGEMENT_SCHEMA = "management";

    private TitanGraphqlManagementStoreFactory() {
    }

    /** The two store modes; the enum names (lowercased) are the config values. */
    enum Mode {
        FILE,
        JDBC;

        static Mode parse(String configuredValue) {
            String normalized = configuredValue == null ? "" : configuredValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "", "file" -> FILE;
                case "jdbc" -> JDBC;
                default -> throw new IllegalStateException(
                        STORE_PROPERTY + " must be 'file' or 'jdbc' but was '" + configuredValue + "'");
            };
        }
    }

    /**
     * Builds the management store from the ambient runtime config (system properties / environment),
     * resolving the jdbc-mode datasource from the Quarkus Arc container. This is the production
     * entry point used by {@link GraphqlManagementMutationSupport#defaultManagementStore()}.
     */
    static TitanGraphqlManagementStore fromRuntimeConfig() {
        Mode mode = Mode.parse(configuredStore());
        if (mode == Mode.FILE) {
            return fileStore();
        }
        return jdbcStore(
                quarkusManagementDataSourceSupplier(),
                resolveDialect(configuredDialect(), null), false);
    }

    /**
     * Builds the jdbc-mode store over an explicit datasource (tests, direct wiring): bootstraps the
     * schema + routines once, then wraps {@link JdbcTransactionalMutationStore} in the durable
     * GraphQL store. Used by the management IT against a live container datasource.
     */
    static TitanGraphqlDurableManagementStore jdbcStore(DataSource dataSource, Dialect dialect) {
        return jdbcStore(() -> dataSource, dialect, true);
    }

    static TitanGraphqlDurableManagementStore jdbcStoreInstalled(DataSource dataSource, Dialect dialect) {
        return jdbcStore(() -> dataSource, dialect, false);
    }

    private static TitanGraphqlDurableManagementStore jdbcStore(
            Supplier<DataSource> dataSourceSupplier,
            Dialect dialect,
            boolean install
    ) {
        DataSource dataSource = dataSourceSupplier.get();
        if (install) {
            bootstrapSchema(dataSource, dialect);
        } else {
            verifySchema(dataSource);
        }
        DataSource schemaScoped = ManagementSchemaDataSource.wrap(dataSource, dialect);
        TransactionalMutationStore titanStore = new JdbcTransactionalMutationStore(schemaScoped);
        return new TitanGraphqlDurableManagementStore(titanStore, schemaScoped);
    }

    private static TitanGraphqlManagementStore fileStore() {
        String transactionLogPath = systemValue(TRANSACTION_LOG_PROPERTY, TRANSACTION_LOG_ENVIRONMENT_VARIABLE);
        if (transactionLogPath.isBlank() == false) {
            return new TitanGraphqlDurableManagementStore(Path.of(transactionLogPath));
        }
        return new TitanGraphqlInMemoryManagementStore();
    }

    // ------------------------------------------------------------------
    // Schema bootstrap.
    // ------------------------------------------------------------------

    /**
     * Ensures the {@code management} schema exists then applies the bundled DDL + routines once.
     * Honest failure: an unreachable database surfaces here as an {@link IllegalStateException}
     * naming the datasource and remedy (configure {@code quarkus.datasource}), never a silent
     * fallback to the file store.
     */
    private static void bootstrapSchema(DataSource dataSource, Dialect dialect) {
        try (Connection connection = dataSource.getConnection()) {
            TitanGraphqlManagementSchemaInstaller.install(connection, dialect);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "jdbc management store (" + STORE_PROPERTY + "=jdbc) could not bootstrap the management"
                            + " schema/routines: " + failure.getMessage()
                            + ". Remedy: configure a reachable quarkus.datasource.jdbc.url (plus credentials)"
                            + " for the database that carries the management store, or switch "
                            + STORE_PROPERTY + " back to file.",
                    failure);
        }
    }

    private static void verifySchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("jdbc management store requires an installed schema and routines; "
                    + "run TitanGraphqlArtifactJobCli install-management before serving", failure);
        }
    }

    // ------------------------------------------------------------------
    // Datasource resolution (Quarkus Arc) + dialect detection.
    // ------------------------------------------------------------------

    private static Supplier<DataSource> quarkusManagementDataSourceSupplier() {
        return () -> {
            io.quarkus.arc.ArcContainer container = io.quarkus.arc.Arc.container();
            io.quarkus.arc.InstanceHandle<DataSource> handle =
                    container == null ? null : container.instance(DataSource.class);
            if (handle == null || handle.isAvailable() == false) {
                throw new IllegalStateException(
                        "jdbc management store (" + STORE_PROPERTY + "=jdbc) could not be initialised:"
                                + " no Quarkus datasource bean is available."
                                + " Remedy: configure quarkus.datasource.jdbc.url (plus credentials) for the"
                                + " database that carries the management store, or switch "
                                + STORE_PROPERTY + " back to file.");
            }
            return handle.get();
        };
    }

    /**
     * Resolves the management SQL dialect: an explicit {@code titan.graphql.management.dialect}
     * override wins; otherwise the JDBC URL of the supplied datasource (or {@code db-kind} config)
     * is inspected. Defaults to PostgreSQL — the campaign's primary dialect.
     */
    static Dialect resolveDialect(String configuredDialect, DataSource dataSource) {
        if (configuredDialect != null && configuredDialect.isBlank() == false) {
            return Dialect.fromId(configuredDialect.trim());
        }
        String url = jdbcUrl(dataSource);
        if (url == null || url.isBlank()) {
            url = configuredDbKind();
        }
        String normalized = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (normalized.contains("mysql") || normalized.contains("mariadb")) {
            return Dialect.MYSQL;
        }
        return Dialect.POSTGRESQL;
    }

    private static String jdbcUrl(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException unreachable) {
            return null;
        }
    }

    private static String configuredDbKind() {
        try {
            return org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.datasource.db-kind", String.class)
                    .orElse("");
        } catch (RuntimeException noConfig) {
            return "";
        }
    }

    private static String configuredStore() {
        String value = systemValue(STORE_PROPERTY, STORE_ENVIRONMENT_VARIABLE);
        if (value.isBlank()) {
            value = configValue(STORE_PROPERTY);
        }
        return value;
    }

    private static String configuredDialect() {
        String value = System.getProperty(DIALECT_PROPERTY, "");
        if (value.isBlank()) {
            value = configValue(DIALECT_PROPERTY);
        }
        return value;
    }

    private static String systemValue(String property, String environmentVariable) {
        String value = System.getProperty(property, "");
        if (value.isBlank()) {
            String fromEnvironment = System.getenv(environmentVariable);
            value = fromEnvironment == null ? "" : fromEnvironment;
        }
        return value;
    }

    private static String configValue(String key) {
        try {
            return org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue(key, String.class)
                    .orElse("");
        } catch (RuntimeException noConfig) {
            return "";
        }
    }

    /**
     * A {@link DataSource} that defers to a delegate but defaults every connection's search path to
     * the {@code management} schema (PostgreSQL {@code SET search_path}) so the bundled bare-named DDL
     * and the routine bodies' unqualified table references both resolve there — the same seam core's
     * dogfood IT uses. On MySQL the schema IS the database (selected via {@code USE}); the wrap is a
     * pass-through.
     */
    private static final class ManagementSchemaDataSource implements DataSource {

        private final DataSource delegate;
        private final String initSql;

        private ManagementSchemaDataSource(DataSource delegate, String initSql) {
            this.delegate = Objects.requireNonNull(delegate, "delegate datasource");
            this.initSql = initSql;
        }

        static DataSource wrap(DataSource delegate, Dialect dialect) {
            String initSql = dialect == Dialect.POSTGRESQL
                    ? "SET search_path TO " + MANAGEMENT_SCHEMA
                    : "USE " + MANAGEMENT_SCHEMA;
            return new ManagementSchemaDataSource(delegate, initSql);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return prepare(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return prepare(delegate.getConnection(username, password));
        }

        private Connection prepare(Connection connection) throws SQLException {
            if (initSql != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(initSql);
                }
            }
            return connection;
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }
}
