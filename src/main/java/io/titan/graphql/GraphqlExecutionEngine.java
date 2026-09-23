package io.titan.graphql;

import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentity;
import io.titan.graphql.artifact.TitanGraphqlEntryPointRef;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentity;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.artifact.TitanGraphqlSqlRoutineRef;
import io.titan.graphql.database.DatabaseGraphqlWholeRequestRuntime;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.sqlmode.GraphqlSqlModeRuntime;
import io.titan.graphql.sqlmode.GraphqlSqlModeUnavailableException;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Selects the engine that answers application {@code /graphql} requests. The {@code database}
 * mode is the production cutover path: it makes one JDBC call to the Titan-transpiled,
 * database-resident whole-request engine. The remaining modes are temporary migration/reference
 * paths and must not become a fallback from {@code database}.
 *
 * <p>Configured by {@code titan.graphql.execution.mode} ({@code java} | {@code jdbc} |
 * {@code compiled} | {@code sql} | {@code database}, default {@code database}) — an ordinary
 * MicroProfile/Quarkus
 * config property, so
 * {@code application.properties}, {@code -Dtitan.graphql.execution.mode=...} and the
 * {@code TITAN_GRAPHQL_EXECUTION_MODE} environment variable all work. An unknown value fails
 * fast and descriptively; there is no silent defaulting on bad input. The application-serving
 * CDI wiring permits only {@code database}; an explicit test/reference-only opt-in is required
 * before a legacy executor may answer a request.</p>
 *
 * <p>In compiled, SQL, and database modes requests route over the Quarkus default datasource (Agroal,
 * {@code quarkus.datasource.*}); all require the reviewed model path and the exact
 * model/package binding emitted by {@code titanGraphqlBindPackage}. The surfaced deployment
 * fingerprint identifies that combined binding, not merely an unbound SQL artifact.
 * The admin/management plane ({@code /admin/graphql}) is not affected — only the application
 * kernel is transpiled and deployable.</p>
 */
@Singleton
public class GraphqlExecutionEngine {

    public static final String MODE_PROPERTY = "titan.graphql.execution.mode";
    public static final String MODE_ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_EXECUTION_MODE";
    /**
     * Test/reference-only escape hatch for legacy in-process engines. It defaults to false so a
     * production configuration override of {@link #MODE_PROPERTY} cannot re-enable JVM GraphQL.
     */
    public static final String ALLOW_LEGACY_EXECUTION_MODES_PROPERTY =
            "titan.graphql.allow-legacy-execution-modes";
    public static final String MODEL_PATH_PROPERTY = "titan.graphql.model.path";
    public static final String MODEL_PATH_ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_MODEL_PATH";
    /** Explicit dialect required when {@linkplain Mode#DATABASE database mode} is selected. */
    public static final String DATABASE_ENGINE_DIALECT_PROPERTY = "titan.graphql.database-engine.dialect";
    public static final String DATABASE_ENGINE_DIALECT_ENVIRONMENT_VARIABLE =
            "TITAN_GRAPHQL_DATABASE_ENGINE_DIALECT";
    static final String QUARKUS_DATASOURCE_DESCRIPTION =
            "Quarkus default datasource (quarkus.datasource.jdbc.url)";
    static final String FINGERPRINT_UNAVAILABLE = "unavailable";
    private static final List<String> WHOLE_REQUEST_ENTRY_POINT_PARAMETERS = List.of(
            "java.sql.Connection",
            "java.lang.String", // query
            "java.lang.String", // operation name
            "java.lang.String", // variables JSON
            "java.lang.String", // extensions JSON
            "java.lang.String", // trusted context JSON
            "boolean",          // allow mutations
            "java.lang.String", // expected model semantic hash
            "java.lang.String", // expected runtime identity
            "java.lang.String"  // expected package inventory identity
    );

    /** The execution modes; the enum names (lowercased) are the config values. */
    public enum Mode {
        JAVA,
        JDBC,
        COMPILED,
        SQL,
        DATABASE;

        static Mode parse(String configuredValue) {
            String normalized = configuredValue == null ? "" : configuredValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "" -> DATABASE;
                case "java" -> JAVA;
                case "jdbc" -> JDBC;
                case "compiled" -> COMPILED;
                case "sql" -> SQL;
                case "database" -> DATABASE;
                default -> throw new IllegalStateException(
                        MODE_PROPERTY + " must be 'java', 'jdbc', 'compiled', 'sql', or 'database' but was '"
                                + configuredValue + "'");
            };
        }
    }

    private final Mode mode;
    private final Supplier<DataSource> dataSourceSupplier;
    private final String dataSourceDescription;
    private final String modelPath;
    private final GraphqlApplicationMutationProvider mutationProvider;
    private final String databaseEngineDialect;
    private final boolean legacyExecutionModesAllowed;

    private volatile GraphqlSqlModeRuntime sqlRuntime;
    private volatile GenericJdbcGraphqlRuntime genericJdbcRuntime;
    private volatile TitanCompiledGraphqlRuntime compiledRuntime;
    private volatile DatabaseGraphqlWholeRequestRuntime databaseRuntime;
    private volatile TitanGraphqlPackageBinding packageBinding;
    private volatile TitanGraphqlGap005ArtifactMetadata packageMetadata;
    private volatile TitanGraphqlModelDocument packageModel;
    private volatile String fingerprint;

    /** CDI wiring: mode from MicroProfile config, datasource from the Agroal default bean. */
    @Inject
    public GraphqlExecutionEngine(
            @ConfigProperty(name = MODE_PROPERTY, defaultValue = "database") String configuredMode,
            @ConfigProperty(name = MODEL_PATH_PROPERTY, defaultValue = "__unset__") String configuredModelPath,
            @ConfigProperty(name = DATABASE_ENGINE_DIALECT_PROPERTY, defaultValue = "__unset__")
                    String configuredDatabaseEngineDialect,
            @ConfigProperty(name = ALLOW_LEGACY_EXECUTION_MODES_PROPERTY, defaultValue = "false")
                    boolean configuredLegacyExecutionModesAllowed,
            Instance<DataSource> dataSources,
            Instance<GraphqlApplicationMutationProvider> mutationProviders
    ) {
        this(configuredMode, quarkusDataSourceSupplier(dataSources), QUARKUS_DATASOURCE_DESCRIPTION,
                configuredModelPath, combineMutationProviders(mutationProviders), configuredDatabaseEngineDialect,
                configuredLegacyExecutionModesAllowed);
    }

    /** Compatibility wiring for direct tests and embedding without custom mutations. */
    public GraphqlExecutionEngine(
            String configuredMode,
            String configuredModelPath,
            Instance<DataSource> dataSources
    ) {
        this(configuredMode, quarkusDataSourceSupplier(dataSources), QUARKUS_DATASOURCE_DESCRIPTION,
                configuredModelPath, GraphqlApplicationMutationProvider.none(), configuredDatabaseEngineDialect(), true);
    }

    /** Direct wiring (tests, non-CDI use): explicit mode, datasource source, and description. */
    public GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription
    ) {
        this(configuredMode, dataSourceSupplier, dataSourceDescription, configuredModelPath(),
                GraphqlApplicationMutationProvider.none(), configuredDatabaseEngineDialect(), true);
    }

    public GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            String modelPath
    ) {
        this(configuredMode, dataSourceSupplier, dataSourceDescription, modelPath,
                GraphqlApplicationMutationProvider.none(), configuredDatabaseEngineDialect(), true);
    }

    /** Direct wiring with explicit application-owned custom mutation handlers. */
    public GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            String modelPath,
            GraphqlApplicationMutationProvider mutationProvider
    ) {
        this(configuredMode, dataSourceSupplier, dataSourceDescription, modelPath, mutationProvider,
                configuredDatabaseEngineDialect(), true);
    }

    /**
     * Direct wiring with an explicit whole-request database dialect. Kept public so embedded
     * deployments can avoid global process configuration and make their database target clear.
     */
    public GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            String modelPath,
            GraphqlApplicationMutationProvider mutationProvider,
            String configuredDatabaseEngineDialect
    ) {
        this(configuredMode, dataSourceSupplier, dataSourceDescription, modelPath, mutationProvider,
                configuredDatabaseEngineDialect, true);
    }

    /**
     * Internal serving wiring with an explicit legacy-mode policy. Direct constructors above
     * retain their test/reference compatibility; production CDI must use this constructor with
     * the default-deny configuration value instead.
     */
    GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            String modelPath,
            GraphqlApplicationMutationProvider mutationProvider,
            String configuredDatabaseEngineDialect,
            boolean legacyExecutionModesAllowed
    ) {
        this.mode = Mode.parse(configuredMode);
        this.dataSourceSupplier = Objects.requireNonNull(dataSourceSupplier, "dataSourceSupplier");
        this.dataSourceDescription = Objects.requireNonNull(dataSourceDescription, "dataSourceDescription");
        String normalizedModelPath = modelPath == null ? "" : modelPath.trim();
        this.modelPath = "__unset__".equals(normalizedModelPath) ? "" : normalizedModelPath;
        this.mutationProvider = mutationProvider == null
                ? GraphqlApplicationMutationProvider.none() : mutationProvider;
        this.databaseEngineDialect = "__unset__".equals(
                configuredDatabaseEngineDialect == null ? "" : configuredDatabaseEngineDialect.trim())
                ? "" : configuredDatabaseEngineDialect;
        this.legacyExecutionModesAllowed = legacyExecutionModesAllowed;
    }

    /**
     * Non-CDI fallback used when the HTTP resource is constructed directly (unit tests,
     * plain JAX-RS): mode from the system property / environment variable, no datasource —
     * database-backed modes then fail descriptively rather than ever falling back to Java mode.
     */
    public static GraphqlExecutionEngine fromSystemConfig() {
        String configured = System.getProperty(MODE_PROPERTY, "");
        if (configured.isBlank()) {
            String fromEnvironment = System.getenv(MODE_ENVIRONMENT_VARIABLE);
            configured = fromEnvironment == null ? "" : fromEnvironment;
        }
        return new GraphqlExecutionEngine(
                configured,
                () -> {
                    throw new IllegalStateException(
                            "no datasource is available outside the Quarkus runtime");
                },
                "none configured (running outside the Quarkus runtime)");
    }

    public Mode mode() {
        return mode;
    }

    /** Config value of the active mode ({@code java}, {@code jdbc}, {@code compiled}, or {@code sql}). */
    public String modeName() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** The runtime answering application requests under the active mode. */
    public GraphqlModelRuntime runtime() {
        requireServingMode();
        if (mode == Mode.JAVA) {
            return GraphqlRuntimeRegistry.activeRuntime();
        }
        if (mode == Mode.JDBC) {
            if (modelPath.isBlank()) {
                throw jdbcUnavailable("no reviewed model path is configured", null);
            }
            GenericJdbcGraphqlRuntime runtime = genericJdbcRuntime;
            if (runtime == null) {
                synchronized (this) {
                    if (genericJdbcRuntime == null) {
                        try {
                            genericJdbcRuntime = GenericJdbcGraphqlRuntime.fromYaml(
                                    Path.of(modelPath), dataSourceSupplier.get());
                        } catch (GraphqlExecutionModeUnavailableException ex) {
                            throw ex;
                        } catch (RuntimeException ex) {
                            throw jdbcUnavailable("the model or datasource could not be initialized ("
                                    + ex.getMessage() + ")", ex);
                        }
                    }
                    runtime = genericJdbcRuntime;
                }
            }
            return runtime;
        }
        if (mode == Mode.COMPILED) {
            TitanCompiledGraphqlRuntime runtime = compiledRuntime;
            if (runtime == null) {
                synchronized (this) {
                    if (compiledRuntime == null) {
                        verifiedPackageBinding();
                        try {
                            compiledRuntime = new TitanCompiledGraphqlRuntime(
                                    packageModel, dataSourceSupplier.get(), dataSourceDescription, packageMetadata,
                                    mutationProvider);
                        } catch (GraphqlExecutionModeUnavailableException ex) {
                            throw ex;
                        } catch (RuntimeException ex) {
                            throw compiledUnavailable("the compiled runtime or datasource could not be initialized ("
                                    + ex.getMessage() + ")", ex);
                        }
                    }
                    runtime = compiledRuntime;
                }
            }
            return runtime;
        }
        if (mode == Mode.DATABASE) {
            DatabaseGraphqlWholeRequestRuntime runtime = databaseRuntime;
            if (runtime == null) {
                synchronized (this) {
                    if (databaseRuntime == null) {
                        DatabaseGraphqlWholeRequestRuntime.Dialect dialect = databaseDialect();
                        TitanGraphqlPackageBinding binding = verifiedPackageBinding();
                        try {
                            databaseRuntime = new DatabaseGraphqlWholeRequestRuntime(
                                    dialect,
                                    () -> dataSourceSupplier.get().getConnection(),
                                    binding.modelSemanticSha256(),
                                    TitanGraphqlDatabaseRuntimeIdentity.read(
                                            TitanGraphqlArtifactsDirectory.configuredDirectory()),
                                    TitanGraphqlDatabasePackageIdentity.read(
                                            TitanGraphqlArtifactsDirectory.configuredDirectory()),
                                    dataSourceDescription,
                                    verifiedDatabaseEntryPoint(packageMetadata, dialect));
                        } catch (GraphqlExecutionModeUnavailableException ex) {
                            throw ex;
                        } catch (RuntimeException ex) {
                            throw databaseUnavailable("the whole-request runtime could not be initialized ("
                                    + ex.getMessage() + ")", ex);
                        }
                    }
                    runtime = databaseRuntime;
                }
            }
            return runtime;
        }
        GraphqlSqlModeRuntime runtime = sqlRuntime;
        if (runtime == null) {
            synchronized (this) {
                if (sqlRuntime == null) {
                    verifiedPackageBinding();
                    sqlRuntime = new GraphqlSqlModeRuntime(
                            dataSourceSupplier, dataSourceDescription, packageMetadata,
                            packageBinding.modelSemanticSha256());
                }
                runtime = sqlRuntime;
            }
        }
        return runtime;
    }

    /**
     * Rejects a process-configured legacy executor before the HTTP resource can parse a GraphQL
     * document locally. Package-private so the resource can invoke it ahead of its historical
     * GET-only parser guard; direct reference fixtures deliberately use the compatibility policy.
     */
    void requireServingMode() {
        if (mode != Mode.DATABASE && !legacyExecutionModesAllowed) {
            throw new GraphqlExecutionModeUnavailableException(
                    "legacy execution mode '" + modeName() + "' is disabled for the serving application; "
                            + "deploy the database whole-request engine or set "
                            + ALLOW_LEGACY_EXECUTION_MODES_PROPERTY
                            + "=true only in an explicit test/reference environment");
        }
    }

    /**
     * Deployment fingerprint for compiled/SQL/database mode: a stable hash over the exact reviewed model
     * and Titan package binding; empty in Java/JDBC mode. An unreadable or
     * mismatched binding yields {@code unavailable}; runtime execution still refuses the package.
     */
    public String fingerprint() {
        if (mode != Mode.SQL && mode != Mode.COMPILED && mode != Mode.DATABASE) {
            return "";
        }
        String value = fingerprint;
        if (value == null) {
            synchronized (this) {
                if (fingerprint == null) {
                    try {
                        fingerprint = verifiedPackageBinding().deploymentFingerprint();
                    } catch (RuntimeException unreadable) {
                        fingerprint = FINGERPRINT_UNAVAILABLE;
                    }
                }
                value = fingerprint;
            }
        }
        return value;
    }

    private static String configuredModelPath() {
        String configured = System.getProperty(MODEL_PATH_PROPERTY, "");
        if (configured.isBlank()) {
            String environment = System.getenv(MODEL_PATH_ENVIRONMENT_VARIABLE);
            configured = environment == null ? "" : environment;
        }
        return configured;
    }

    private static String configuredDatabaseEngineDialect() {
        String configured = System.getProperty(DATABASE_ENGINE_DIALECT_PROPERTY, "");
        if (configured.isBlank()) {
            String environment = System.getenv(DATABASE_ENGINE_DIALECT_ENVIRONMENT_VARIABLE);
            configured = environment == null ? "" : environment;
        }
        return configured;
    }

    private DatabaseGraphqlWholeRequestRuntime.Dialect databaseDialect() {
        String normalized = databaseEngineDialect == null ? ""
                : databaseEngineDialect.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "postgres", "postgresql" -> DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL;
            case "mysql" -> DatabaseGraphqlWholeRequestRuntime.Dialect.MYSQL;
            case "" -> throw databaseUnavailable("no database dialect is configured; set "
                    + DATABASE_ENGINE_DIALECT_PROPERTY + " to 'postgresql' or 'mysql'", null);
            default -> throw databaseUnavailable(DATABASE_ENGINE_DIALECT_PROPERTY
                    + " must be 'postgresql' or 'mysql' but was '" + databaseEngineDialect + "'", null);
        };
    }

    /**
     * Resolves the only routine that the database mode is allowed to call. This proves that the
     * exact bound package contains a generated whole-request implementation for the configured
     * dialect; a carrier package, a legacy demo function, or an arbitrary configured routine
     * cannot cross this boundary.
     */
    private DatabaseGraphqlWholeRequestRuntime.EntryPoint verifiedDatabaseEntryPoint(
            TitanGraphqlGap005ArtifactMetadata metadata,
            DatabaseGraphqlWholeRequestRuntime.Dialect dialect
    ) {
        if (metadata == null) {
            throw databaseUnavailable("verified package metadata is unavailable", null);
        }
        String dialectName = dialect == DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL
                ? "postgresql" : "mysql";
        String expectedKind = dialect == DatabaseGraphqlWholeRequestRuntime.Dialect.POSTGRESQL
                ? "function" : "procedure";
        for (TitanGraphqlEntryPointRef entryPoint : metadata.entryPoints()) {
            if (!entryPoint.className().startsWith("io.titan.graphql.database.generated.")
                    || !"executeGraphqlRequest".equals(entryPoint.methodName())
                    || !WHOLE_REQUEST_ENTRY_POINT_PARAMETERS.equals(entryPoint.parameterTypes())) {
                continue;
            }
            for (TitanGraphqlSqlRoutineRef routine : entryPoint.routines()) {
                if (dialectName.equals(routine.dialect())
                        && "execute_graphql_request".equals(routine.routineName())
                        && expectedKind.equals(routine.objectKind())) {
                    return new DatabaseGraphqlWholeRequestRuntime.EntryPoint(
                            routine.schemaName(), routine.routineName());
                }
            }
        }
        throw databaseUnavailable("the bound package does not publish the generated nine-input "
                + dialectName + " " + expectedKind + " execute_graphql_request entry point", null);
    }

    private GraphqlExecutionModeUnavailableException jdbcUnavailable(String cause, Throwable failure) {
        String message = "JDBC execution mode (" + MODE_PROPERTY + "=jdbc) could not answer this request: "
                + cause + " [model: " + (modelPath.isBlank() ? "not configured" : modelPath)
                + "; datasource: " + dataSourceDescription + "]. Remedy: set " + MODEL_PATH_PROPERTY
                + " (or " + MODEL_PATH_ENVIRONMENT_VARIABLE + ") to a reviewed titan.graphql.yaml, configure "
                + "the datasource for that schema, or switch " + MODE_PROPERTY + " back to java.";
        return failure == null
                ? new GraphqlExecutionModeUnavailableException(message)
                : new GraphqlExecutionModeUnavailableException(message, failure);
    }

    private TitanGraphqlPackageBinding verifiedPackageBinding() {
        TitanGraphqlPackageBinding binding = packageBinding;
        if (binding != null) {
            return binding;
        }
        synchronized (this) {
            if (packageBinding == null) {
                if (modelPath.isBlank()) {
                    throw packageUnavailable("no reviewed model path is configured", null);
                }
                try {
                    Path path = Path.of(modelPath);
                    TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(Files.readString(path));
                    TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
                    if (report.blocksDeployment()) {
                        throw new IllegalStateException("the reviewed model has " + report.errorCount()
                                + " deployment-blocking validation error(s)");
                    }
                    TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata();
                    TitanGraphqlPackageBinding candidate = TitanGraphqlPackageBinding.read(
                            TitanGraphqlArtifactsDirectory.configuredDirectory());
                    candidate.verify(document, metadata);
                    packageMetadata = metadata;
                    packageModel = document;
                    packageBinding = candidate;
                } catch (GraphqlExecutionModeUnavailableException ex) {
                    throw ex;
                } catch (IOException | RuntimeException ex) {
                    throw packageUnavailable("the reviewed model/package binding could not be verified ("
                            + ex.getMessage() + ")", ex);
                }
            }
            return packageBinding;
        }
    }

    private GraphqlSqlModeUnavailableException sqlUnavailable(String cause, Throwable failure) {
        String detail = cause + " [model: " + (modelPath.isBlank() ? "not configured" : modelPath)
                + "; package: " + TitanGraphqlArtifactsDirectory.configuredDirectory() + "]. "
                + "Set " + MODEL_PATH_PROPERTY + " (or " + MODEL_PATH_ENVIRONMENT_VARIABLE
                + ") and run titanGraphqlBindPackage for that reviewed model before enabling "
                + modeName() + " mode.";
        return GraphqlSqlModeUnavailableException.describe(dataSourceDescription, detail, failure);
    }

    private GraphqlExecutionModeUnavailableException compiledUnavailable(String cause, Throwable failure) {
        String message = "Titan-compiled execution mode (" + MODE_PROPERTY
                + "=compiled) could not answer this request: " + cause
                + " [model: " + (modelPath.isBlank() ? "not configured" : modelPath)
                + "; package: " + TitanGraphqlArtifactsDirectory.configuredDirectory()
                + "; datasource: " + dataSourceDescription + "]. Remedy: set " + MODEL_PATH_PROPERTY
                + " to the reviewed model, run titanGraphqlBindPackage, deploy that package, and configure "
                + "the datasource for the target database.";
        return failure == null
                ? new GraphqlExecutionModeUnavailableException(message)
                : new GraphqlExecutionModeUnavailableException(message, failure);
    }

    private GraphqlExecutionModeUnavailableException databaseUnavailable(String cause, Throwable failure) {
        String message = "Database whole-request execution mode (" + MODE_PROPERTY
                + "=database) could not answer this request: " + cause
                + " [model: " + (modelPath.isBlank() ? "not configured" : modelPath)
                + "; package: " + TitanGraphqlArtifactsDirectory.configuredDirectory()
                + "; datasource: " + dataSourceDescription + "]. Remedy: set " + MODEL_PATH_PROPERTY
                + " to the reviewed model, run titanGraphqlBindPackage, deploy the generated whole-request "
                + "engine, configure " + DATABASE_ENGINE_DIALECT_PROPERTY + " as postgresql or mysql, and "
                + "configure the datasource for that database.";
        return failure == null
                ? new GraphqlExecutionModeUnavailableException(message)
                : new GraphqlExecutionModeUnavailableException(message, failure);
    }

    private GraphqlExecutionModeUnavailableException packageUnavailable(String cause, Throwable failure) {
        return switch (mode) {
            case COMPILED -> compiledUnavailable(cause, failure);
            case DATABASE -> databaseUnavailable(cause, failure);
            default -> sqlUnavailable(cause, failure);
        };
    }

    private static Supplier<DataSource> quarkusDataSourceSupplier(Instance<DataSource> dataSources) {
        Objects.requireNonNull(dataSources, "dataSources");
        return () -> {
            if (dataSources.isUnsatisfied()) {
                throw new IllegalStateException("no Quarkus datasource bean is available");
            }
            return dataSources.get();
        };
    }

    private static GraphqlApplicationMutationProvider combineMutationProviders(
            Iterable<GraphqlApplicationMutationProvider> providers
    ) {
        if (providers == null) return GraphqlApplicationMutationProvider.none();
        List<GraphqlMutationDescriptor> descriptors = new ArrayList<>();
        Map<String, GraphqlMutationCommandHandler> handlers = new LinkedHashMap<>();
        Map<String, GraphqlMutationAuditSink> sinksByCommand = new LinkedHashMap<>();
        for (GraphqlApplicationMutationProvider provider : providers) {
            if (provider == null) continue;
            GraphqlApplicationMutationProvider snapshot = GraphqlApplicationMutationProvider.of(
                    provider.descriptors(), provider.handlers(), provider.auditSink());
            descriptors.addAll(snapshot.descriptors());
            for (Map.Entry<String, GraphqlMutationCommandHandler> entry : snapshot.handlers().entrySet()) {
                if (handlers.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalStateException("duplicate custom mutation handler command '"
                            + entry.getKey() + "'");
                }
            }
            for (GraphqlMutationDescriptor descriptor : snapshot.descriptors()) {
                sinksByCommand.put(descriptor.commandName(), snapshot.auditSink());
            }
        }
        GraphqlMutationAuditSink combinedSink = event -> {
            GraphqlMutationAuditSink sink = sinksByCommand.get(event.commandName());
            if (sink != null) sink.record(event);
        };
        return GraphqlApplicationMutationProvider.of(descriptors, handlers, combinedSink);
    }
}
