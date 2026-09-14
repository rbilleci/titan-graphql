package io.titan.graphql;

import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
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
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Selects the engine that answers application {@code /graphql} requests (completion plan
 * W5.1): generated compiled carriers (default), the in-JVM Java proof kernel, generic JDBC reads,
 * or the legacy deployed whole-request SQL functions.
 *
 * <p>Configured by {@code titan.graphql.execution.mode} ({@code java} | {@code jdbc} |
 * {@code compiled} | {@code sql}, default {@code compiled}) — an ordinary MicroProfile/Quarkus
 * config property, so
 * {@code application.properties}, {@code -Dtitan.graphql.execution.mode=...} and the
 * {@code TITAN_GRAPHQL_EXECUTION_MODE} environment variable all work. An unknown value fails
 * fast and descriptively; there is no silent defaulting on bad input.</p>
 *
 * <p>In compiled and SQL modes requests route over the Quarkus default datasource (Agroal,
 * {@code quarkus.datasource.*}); both require the reviewed model path and the exact
 * model/package binding emitted by {@code titanGraphqlBindPackage}. The surfaced deployment
 * fingerprint identifies that combined binding, not merely an unbound SQL artifact.
 * The admin/management plane ({@code /admin/graphql}) is not affected — only the application
 * kernel is transpiled and deployable.</p>
 */
@Singleton
public class GraphqlExecutionEngine {

    public static final String MODE_PROPERTY = "titan.graphql.execution.mode";
    public static final String MODE_ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_EXECUTION_MODE";
    public static final String MODEL_PATH_PROPERTY = "titan.graphql.model.path";
    public static final String MODEL_PATH_ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_MODEL_PATH";
    static final String QUARKUS_DATASOURCE_DESCRIPTION =
            "Quarkus default datasource (quarkus.datasource.jdbc.url)";
    static final String FINGERPRINT_UNAVAILABLE = "unavailable";

    /** The execution modes; the enum names (lowercased) are the config values. */
    public enum Mode {
        JAVA,
        JDBC,
        COMPILED,
        SQL;

        static Mode parse(String configuredValue) {
            String normalized = configuredValue == null ? "" : configuredValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "" -> COMPILED;
                case "java" -> JAVA;
                case "jdbc" -> JDBC;
                case "compiled" -> COMPILED;
                case "sql" -> SQL;
                default -> throw new IllegalStateException(
                        MODE_PROPERTY + " must be 'java', 'jdbc', 'compiled', or 'sql' but was '"
                                + configuredValue + "'");
            };
        }
    }

    private final Mode mode;
    private final Supplier<DataSource> dataSourceSupplier;
    private final String dataSourceDescription;
    private final String modelPath;

    private volatile GraphqlSqlModeRuntime sqlRuntime;
    private volatile GenericJdbcGraphqlRuntime genericJdbcRuntime;
    private volatile TitanCompiledGraphqlRuntime compiledRuntime;
    private volatile TitanGraphqlPackageBinding packageBinding;
    private volatile TitanGraphqlGap005ArtifactMetadata packageMetadata;
    private volatile TitanGraphqlModelDocument packageModel;
    private volatile String fingerprint;

    /** CDI wiring: mode from MicroProfile config, datasource from the Agroal default bean. */
    @Inject
    public GraphqlExecutionEngine(
            @ConfigProperty(name = MODE_PROPERTY, defaultValue = "compiled") String configuredMode,
            @ConfigProperty(name = MODEL_PATH_PROPERTY, defaultValue = "__unset__") String configuredModelPath,
            Instance<DataSource> dataSources
    ) {
        this(configuredMode, quarkusDataSourceSupplier(dataSources), QUARKUS_DATASOURCE_DESCRIPTION,
                configuredModelPath);
    }

    /** Direct wiring (tests, non-CDI use): explicit mode, datasource source, and description. */
    public GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription
    ) {
        this(configuredMode, dataSourceSupplier, dataSourceDescription, configuredModelPath());
    }

    public GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription,
            String modelPath
    ) {
        this.mode = Mode.parse(configuredMode);
        this.dataSourceSupplier = Objects.requireNonNull(dataSourceSupplier, "dataSourceSupplier");
        this.dataSourceDescription = Objects.requireNonNull(dataSourceDescription, "dataSourceDescription");
        String normalizedModelPath = modelPath == null ? "" : modelPath.trim();
        this.modelPath = "__unset__".equals(normalizedModelPath) ? "" : normalizedModelPath;
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
                                    packageModel, dataSourceSupplier.get(), dataSourceDescription, packageMetadata);
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
     * Deployment fingerprint for compiled/SQL mode: a stable hash over the exact reviewed model
     * and Titan package binding; empty in Java/JDBC mode. An unreadable or
     * mismatched binding yields {@code unavailable}; runtime execution still refuses the package.
     */
    public String fingerprint() {
        if (mode != Mode.SQL && mode != Mode.COMPILED) {
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

    private GraphqlExecutionModeUnavailableException packageUnavailable(String cause, Throwable failure) {
        return mode == Mode.COMPILED ? compiledUnavailable(cause, failure) : sqlUnavailable(cause, failure);
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
}
