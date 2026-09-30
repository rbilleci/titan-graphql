package io.titan.graphql.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.TitanGraphqlModelImportService;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementSchemaInstaller;
import io.titan.graphql.management.TitanGraphqlPreviewDeploymentPublisher;
import io.titan.graphql.management.TitanGraphqlPreviewDraftExporter;
import io.titan.graphql.management.TitanGraphqlPreviewPackageStager;
import io.titan.graphql.management.TitanGraphqlPreviewPublicationService;
import io.titan.management.JdbcTransactionalMutationStore;
import io.titan.management.ManagementSchemaInstaller;
import java.net.InetSocketAddress;
import io.titan.runtime.jdbc.SingleConnectionDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

public final class TitanGraphqlArtifactJobCli {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_ENV = "TITAN_GRAPHQL_CONTROL_DB_USER";
    private static final String PASSWORD_ENV = "TITAN_GRAPHQL_CONTROL_DB_PASSWORD";

    private TitanGraphqlArtifactJobCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(usage());
        }
        String action = args[0];
        int expectedLength = switch (action) {
            case "request" -> 7;
            case "run-one", "run-one-validation", "run-one-import", "run-one-review" -> 5;
            case "run-worker" -> args.length == 7 ? 7 : 6;
            case "status" -> 4;
            case "export-preview-draft" -> 5;
            case "stage-preview-package" -> 6;
            case "publish-preview" -> 11;
            case "serve-api" -> 4;
            case "check-worker-ready" -> 4;
            case "install-management", "repair-management" -> 3;
            default -> throw new IllegalArgumentException(usage());
        };
        if (args.length != expectedLength) {
            throw new IllegalArgumentException(usage());
        }
        if (action.equals("request") && !args[6].equals("true") && !args[6].equals("false")) {
            throw new IllegalArgumentException("enable-introspection must be true or false");
        }
        if ((action.equals("run-one") || action.equals("run-one-validation")
                || action.equals("run-one-import") || action.equals("run-one-review")
                || action.equals("run-worker"))
                && (Long.parseLong(args[4]) <= 0L || Long.parseLong(args[4]) > 3600L)) {
            throw new IllegalArgumentException("lease-seconds must be positive and at most 3600");
        }
        if (action.equals("run-worker")
                && (Long.parseLong(args[5]) < 50L || Long.parseLong(args[5]) > 60_000L)) {
            throw new IllegalArgumentException("poll-millis must be between 50 and 60000");
        }
        if (action.equals("run-worker") && args.length == 7 && Long.parseLong(args[6]) <= 0L) {
            throw new IllegalArgumentException("max-jobs must be positive");
        }
        if ((action.equals("run-worker") || action.equals("check-worker-ready")) && !args[3].equals("jdbc")) {
            throw new IllegalArgumentException("continuous artifact worker requires JDBC management state");
        }
        if ((action.equals("run-one-import") || action.equals("run-one-review"))
                && !args[3].equals("jdbc")) {
            throw new IllegalArgumentException("model import and operation review workers require JDBC management state");
        }
        if ((action.equals("run-one") || action.equals("run-one-validation")) && !args[3].equals("jdbc")) {
            Path log = Path.of(args[3]);
            Path state = log.resolveSibling(log.getFileName() + ".graphql-state.jsonl");
            if (!Files.isRegularFile(log) && !Files.isRegularFile(state)) {
                throw new IllegalArgumentException("management transaction log does not exist");
            }
        }
        TitanGraphqlControlJobQueue.Dialect dialect = TitanGraphqlControlJobQueue.Dialect.valueOf(
                args[1].toUpperCase(java.util.Locale.ROOT));
        String jdbcUrl = args[2];
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("control-plane JDBC URL is required");
        }
        String user = System.getenv(USER_ENV);
        String password = System.getenv(PASSWORD_ENV);
        if (action.equals("export-preview-draft")) {
            DataSource managementDatabase = new DriverManagerDataSource(jdbcUrl, user, password, dialect);
            try (Connection connection = managementDatabase.getConnection()) {
                TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
            }
            TitanGraphqlPreviewDraftExporter.Export exported = TitanGraphqlPreviewDraftExporter.export(
                    managementStore("jdbc", managementDatabase), args[3], Path.of(args[4]));
            System.out.println(JSON.writeValueAsString(exported));
            return;
        }
        if (action.equals("stage-preview-package")) {
            DataSource managementDatabase = new DriverManagerDataSource(jdbcUrl, user, password, dialect);
            try (Connection connection = managementDatabase.getConnection()) {
                TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
            }
            TitanGraphqlPreviewPackageStager.Stage staged = TitanGraphqlPreviewPackageStager.stage(
                    managementStore("jdbc", managementDatabase), args[3], Path.of(args[4]), args[1],
                    Path.of(args[5]));
            System.out.println(JSON.writeValueAsString(staged));
            return;
        }
        if (action.equals("publish-preview")) {
            Instant expiresAt;
            try {
                expiresAt = Instant.parse(args[9]);
            } catch (java.time.format.DateTimeParseException invalid) {
                throw new IllegalArgumentException("preview expiration must be an ISO-8601 instant", invalid);
            }
            DataSource managementDatabase = new DriverManagerDataSource(jdbcUrl, user, password, dialect);
            DataSource servingDatabase = new DriverManagerDataSource(jdbcUrl, user, password, dialect, false);
            try (Connection connection = managementDatabase.getConnection()) {
                TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
            }
            TitanGraphqlDurableManagementStore store = managementStore("jdbc", managementDatabase);
            TitanGraphqlPreviewDeploymentPublisher.Publication published =
                    new TitanGraphqlPreviewPublicationService(store, servingDatabase).publish(
                            args[5], args[6], args[7], args[8], expiresAt, args[10],
                            Path.of(args[3]), args[1], Path.of(args[4]));
            System.out.println(JSON.writeValueAsString(Map.of(
                    "manifest", published.manifest(),
                    "descriptor", published.descriptor().toString(),
                    "registry", published.registry().toString())));
            return;
        }
        if (action.equals("serve-api")) {
            int port;
            try {
                port = Integer.parseInt(args[3]);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("control-plane API port must be an integer", invalid);
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("control-plane API port is out of range");
            }
            String token = System.getenv("TITAN_GRAPHQL_CONTROL_API_TOKEN");
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("TITAN_GRAPHQL_CONTROL_API_TOKEN must be configured");
            }
            String host = System.getenv().getOrDefault("TITAN_GRAPHQL_CONTROL_API_HOST", "127.0.0.1");
            if (host.isBlank()) {
                throw new IllegalArgumentException("TITAN_GRAPHQL_CONTROL_API_HOST must not be blank");
            }
            DataSource apiDataSource = new DriverManagerDataSource(jdbcUrl, user, password, dialect);
            try (Connection connection = apiDataSource.getConnection()) {
                TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
                verifyControlJobsInstalled(connection);
            }
            TitanGraphqlControlJobHttpServer server = TitanGraphqlControlJobHttpServer.start(
                    new InetSocketAddress(host, port), apiDataSource, dialect, token);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "control-job-api-shutdown"));
            System.out.println("READY " + server.endpointUri());
            Thread.currentThread().join();
            return;
        }
        if ((action.equals("run-one") || action.equals("run-one-validation")
                || action.equals("run-one-import") || action.equals("run-one-review")
                || action.equals("run-worker") || action.equals("check-worker-ready"))
                && args[3].equals("jdbc")) {
            try (Connection connection = connect(jdbcUrl, user, password)) {
                TitanGraphqlManagementSchemaInstaller.verifyInstalled(connection);
            } catch (SQLException failure) {
                throw new IllegalStateException(
                        "JDBC management store is not installed; run install-management", failure);
            }
        }
        DataSource managementDataSource = args[0].equals("run-one")
                || args[0].equals("run-one-validation") || args[0].equals("run-one-import")
                || args[0].equals("run-one-review")
                || args[0].equals("run-worker")
                ? new DriverManagerDataSource(jdbcUrl, user, password, dialect) : null;
        if (action.equals("check-worker-ready") || action.equals("run-worker")) {
            try (Connection connection = connect(jdbcUrl, user, password)) {
                verifyControlJobsInstalled(connection);
            }
            if (action.equals("check-worker-ready")) {
                System.out.println("READY");
                return;
            }
        }
        if (action.equals("run-worker")) {
            runWorker(dialect, jdbcUrl, user, password, managementDataSource,
                    Duration.ofSeconds(Long.parseLong(args[4])), Long.parseLong(args[5]),
                    args.length == 7 ? Long.parseLong(args[6]) : Long.MAX_VALUE);
            return;
        }
        try (Connection connection = connect(jdbcUrl, user, password)) {
            TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                    new SingleConnectionDataSource(connection), dialect, Clock.systemUTC());
            if (action.equals("request") || action.equals("run-one")
                    || action.equals("run-one-validation") || action.equals("run-one-import")
                    || action.equals("run-one-review")) {
                verifyControlJobsInstalled(connection);
            }
            switch (action) {
                case "install-management" -> {
                    TitanGraphqlManagementSchemaInstaller.install(
                            connection, ManagementSchemaInstaller.Dialect.fromId(args[1]));
                    System.out.println("READY");
                }
                case "repair-management" -> {
                    TitanGraphqlManagementSchemaInstaller.repair(
                            connection, ManagementSchemaInstaller.Dialect.fromId(args[1]));
                    System.out.println("READY");
                }
                case "request" -> {
                    connection.setAutoCommit(false);
                    try {
                        var job = TitanGraphqlArtifactGenerationJobRunner.request(
                                queue, connection, args[3], args[4], args[5], Boolean.parseBoolean(args[6]));
                        connection.commit();
                        System.out.println(JSON.writeValueAsString(job));
                    } catch (Exception failure) {
                        connection.rollback();
                        throw failure;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                }
                case "run-one" -> {
                    TitanGraphqlArtifactGenerationJobRunner runner = new TitanGraphqlArtifactGenerationJobRunner(
                            queue, artifactGenerationService(
                                    managementStore(args[3], managementDataSource)));
                    System.out.println(runner.runOne(Duration.ofSeconds(Long.parseLong(args[4]))));
                }
                case "run-one-validation" -> {
                    TitanGraphqlModelValidationJobRunner runner = new TitanGraphqlModelValidationJobRunner(
                            queue, new TitanGraphqlModelValidationService(
                                    managementStore(args[3], managementDataSource)));
                    System.out.println(runner.runOne(Duration.ofSeconds(Long.parseLong(args[4]))));
                }
                case "run-one-import" -> {
                    TitanGraphqlModelImportJobRunner runner = new TitanGraphqlModelImportJobRunner(
                            queue, () -> new TitanGraphqlModelImportService(
                                    managementStore(args[3], managementDataSource)));
                    System.out.println(runner.runOne(Duration.ofSeconds(Long.parseLong(args[4]))));
                }
                case "run-one-review" -> {
                    TitanGraphqlOperationReviewJobRunner runner = new TitanGraphqlOperationReviewJobRunner(
                            queue, () -> managementStore(args[3], managementDataSource), Clock.systemUTC());
                    System.out.println(runner.runOne(Duration.ofSeconds(Long.parseLong(args[4]))));
                }
                case "status" -> {
                    System.out.println(JSON.writeValueAsString(queue.find(args[3]).orElseThrow(
                            () -> new IllegalArgumentException("control-plane job does not exist"))));
                }
                default -> throw new IllegalArgumentException(usage());
            }
        }
    }

    private static void runWorker(
            TitanGraphqlControlJobQueue.Dialect dialect,
            String jdbcUrl,
            String user,
            String password,
            DataSource managementDataSource,
            Duration lease,
            long pollMillis,
            long maxJobs
    ) throws Exception {
        long handled = 0L;
        int preferredJobType = 0;
        DataSource queueDataSource = new DriverManagerDataSource(jdbcUrl, user, password, dialect, false);
        while (handled < maxJobs) {
            TitanGraphqlControlJobQueue queue = new TitanGraphqlControlJobQueue(
                    queueDataSource, dialect, Clock.systemUTC());
            TitanGraphqlArtifactGenerationJobRunner artifactRunner = new TitanGraphqlArtifactGenerationJobRunner(
                    queue, () -> artifactGenerationService(
                            managementStore("jdbc", managementDataSource)));
            TitanGraphqlModelValidationJobRunner validationRunner = new TitanGraphqlModelValidationJobRunner(
                    queue, () -> new TitanGraphqlModelValidationService(
                            managementStore("jdbc", managementDataSource)));
            TitanGraphqlModelImportJobRunner importRunner = new TitanGraphqlModelImportJobRunner(
                    queue, () -> new TitanGraphqlModelImportService(
                            managementStore("jdbc", managementDataSource)));
            TitanGraphqlOperationReviewJobRunner reviewRunner = new TitanGraphqlOperationReviewJobRunner(
                    queue, () -> managementStore("jdbc", managementDataSource), Clock.systemUTC());
            boolean claimed = switch (preferredJobType) {
                case 0 -> artifactRunner.runOneWithHeartbeat(lease)
                        || validationRunner.runOneWithHeartbeat(lease) || importRunner.runOneWithHeartbeat(lease)
                        || reviewRunner.runOneWithHeartbeat(lease);
                case 1 -> validationRunner.runOneWithHeartbeat(lease)
                        || importRunner.runOneWithHeartbeat(lease) || reviewRunner.runOneWithHeartbeat(lease)
                        || artifactRunner.runOneWithHeartbeat(lease);
                case 2 -> importRunner.runOneWithHeartbeat(lease)
                        || reviewRunner.runOneWithHeartbeat(lease) || artifactRunner.runOneWithHeartbeat(lease)
                        || validationRunner.runOneWithHeartbeat(lease);
                default -> reviewRunner.runOneWithHeartbeat(lease)
                        || artifactRunner.runOneWithHeartbeat(lease) || validationRunner.runOneWithHeartbeat(lease)
                        || importRunner.runOneWithHeartbeat(lease);
            };
            if (claimed) {
                handled++;
                preferredJobType = (preferredJobType + 1) % 4;
            } else {
                Thread.sleep(pollMillis);
            }
        }
    }

    private static Connection connect(String jdbcUrl, String user, String password) throws SQLException {
        return user == null || user.isBlank()
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, user, password == null ? "" : password);
    }

    private static void verifyControlJobsInstalled(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT job_id, job_type, request_key, payload_json, payload_sha256, "
                    + "status, attempt_count, lease_token, lease_until, result_json, failure_code, "
                    + "created_at, finished_at FROM public.titan_graphql_control_jobs WHERE 1 = 0").close();
        }
        if (!constraintColumns(connection, "PRIMARY KEY").containsValue(List.of("job_id"))) {
            throw new SQLException("control jobs table requires job_id primary key");
        }
        if (!constraintColumns(connection, "UNIQUE").containsValue(List.of("job_type", "request_key"))) {
            throw new SQLException("control jobs table requires unique job_type and request_key");
        }
        if (!hasStatusConstraint(connection)) {
            throw new SQLException("control jobs table requires titan_graphql_control_jobs_status check constraint");
        }
        if (!hasReadyIndex(connection)) {
            throw new SQLException("control jobs table requires status, lease_until, created_at, job_id ready index");
        }
    }

    private static boolean hasStatusConstraint(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.table_constraints "
                        + "WHERE table_schema = ? AND table_name = ? "
                        + "AND constraint_name = ? AND constraint_type = 'CHECK'")) {
            statement.setString(1, "public");
            statement.setString(2, "titan_graphql_control_jobs");
            statement.setString(3, "titan_graphql_control_jobs_status");
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean hasReadyIndex(Connection connection) throws SQLException {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        boolean mysql = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT)
                .contains("mysql");
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                mysql ? "public" : null, mysql ? null : "public",
                "titan_graphql_control_jobs", false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    indexes.computeIfAbsent(name, ignored -> new ArrayList<>())
                            .add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
        return indexes.containsValue(List.of("status", "lease_until", "created_at", "job_id"));
    }

    private static Map<String, List<String>> constraintColumns(Connection connection, String type)
            throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT t.constraint_name, k.column_name FROM information_schema.table_constraints t "
                        + "JOIN information_schema.key_column_usage k ON "
                        + "k.table_schema = t.table_schema AND k.table_name = t.table_name "
                        + "AND k.constraint_name = t.constraint_name "
                        + "WHERE t.table_schema = ? AND t.table_name = ? AND t.constraint_type = ? "
                        + "ORDER BY t.constraint_name, k.ordinal_position")) {
            statement.setString(1, "public");
            statement.setString(2, "titan_graphql_control_jobs");
            statement.setString(3, type);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                            .add(rows.getString(2).toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private static TitanGraphqlDurableManagementStore managementStore(
            String location,
            DataSource managementDataSource
    ) {
        if (location.equals("jdbc")) {
            return new TitanGraphqlDurableManagementStore(
                    new JdbcTransactionalMutationStore(managementDataSource), managementDataSource);
        }
        return new TitanGraphqlDurableManagementStore(Path.of(location));
    }

    private static TitanGraphqlArtifactGenerationService artifactGenerationService(
            TitanGraphqlDurableManagementStore store
    ) {
        String registryPath = System.getenv(TitanGraphqlArtifactPackageRegistry.ENVIRONMENT_VARIABLE);
        if (registryPath == null || registryPath.isBlank()) {
            return new TitanGraphqlArtifactGenerationService(store);
        }
        TitanGraphqlArtifactPackageRegistry registry = new TitanGraphqlArtifactPackageRegistry(
                Path.of(registryPath));
        return new TitanGraphqlArtifactGenerationService(store, registry::packageDirectory);
    }

    private static String usage() {
        return "usage: TitanGraphqlArtifactJobCli "
                + "install-management <postgresql|mysql> <jdbc-url> | "
                + "repair-management <postgresql|mysql> <jdbc-url> | "
                + "serve-api <postgresql|mysql> <jdbc-url> <port> | "
                + "check-worker-ready <postgresql|mysql> <jdbc-url> jdbc | "
                + "request <postgresql|mysql> <jdbc-url> <request-key> "
                + "<draft-id> <generation-profile> <true|false> | "
                + "run-one <postgresql|mysql> <jdbc-url> <management-log|jdbc> <lease-seconds> | "
                + "run-one-validation <postgresql|mysql> <jdbc-url> <management-log|jdbc> <lease-seconds> | "
                + "run-one-import <postgresql|mysql> <jdbc-url> jdbc <lease-seconds> | "
                + "run-one-review <postgresql|mysql> <jdbc-url> jdbc <lease-seconds> | "
                + "run-worker <postgresql|mysql> <jdbc-url> jdbc "
                + "<lease-seconds> <poll-millis> [max-jobs] | "
                + "export-preview-draft <postgresql|mysql> <jdbc-url> <draft-id> <output.yaml> | "
                + "stage-preview-package <postgresql|mysql> <jdbc-url> <draft-id> "
                + "<package-directory> <package-registry> | "
                + "publish-preview <postgresql|mysql> <jdbc-url> <package-directory> <registry-file> "
                + "<preview-id> <draft-id> <environment> <registry-id> <expires-at> <actor-key> | "
                + "status <postgresql|mysql> <jdbc-url> <job-id>";
    }
}
