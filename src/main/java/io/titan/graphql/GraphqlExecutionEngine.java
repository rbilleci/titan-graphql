package io.titan.graphql;

import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.sqlmode.GraphqlSqlModeRuntime;
import io.titan.graphql.sqlmode.GraphqlSqlModeUnavailableException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Selects the engine that answers application {@code /graphql} requests (completion plan
 * W5.1): the in-JVM Java kernel (default) or the deployed SQL stored functions.
 *
 * <p>Configured by {@code titan.graphql.execution.mode} ({@code java} | {@code sql}, default
 * {@code java}) — an ordinary MicroProfile/Quarkus config property, so
 * {@code application.properties}, {@code -Dtitan.graphql.execution.mode=...} and the
 * {@code TITAN_GRAPHQL_EXECUTION_MODE} environment variable all work. An unknown value fails
 * fast and descriptively; there is no silent defaulting on bad input.</p>
 *
 * <p>In SQL mode requests route over the Quarkus default datasource (Agroal,
 * {@code quarkus.datasource.*}); the deployment fingerprint surfaced next to the mode is the
 * package manifest's {@code artifactId} read via {@link TitanGraphqlArtifactsDirectory}.
 * The admin/management plane ({@code /admin/graphql}) is not affected — only the application
 * kernel is transpiled and deployable.</p>
 */
@Singleton
public class GraphqlExecutionEngine {

    public static final String MODE_PROPERTY = "titan.graphql.execution.mode";
    public static final String MODE_ENVIRONMENT_VARIABLE = "TITAN_GRAPHQL_EXECUTION_MODE";
    static final String QUARKUS_DATASOURCE_DESCRIPTION =
            "Quarkus default datasource (quarkus.datasource.jdbc.url)";
    static final String FINGERPRINT_UNAVAILABLE = "unavailable";

    /** The two execution modes; the enum names (lowercased) are the config values. */
    public enum Mode {
        JAVA,
        SQL;

        static Mode parse(String configuredValue) {
            String normalized = configuredValue == null ? "" : configuredValue.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "", "java" -> JAVA;
                case "sql" -> SQL;
                default -> throw new IllegalStateException(
                        MODE_PROPERTY + " must be 'java' or 'sql' but was '" + configuredValue + "'");
            };
        }
    }

    private final Mode mode;
    private final Supplier<DataSource> dataSourceSupplier;
    private final String dataSourceDescription;

    private volatile GraphqlSqlModeRuntime sqlRuntime;
    private volatile String fingerprint;

    /** CDI wiring: mode from MicroProfile config, datasource from the Agroal default bean. */
    @Inject
    public GraphqlExecutionEngine(
            @ConfigProperty(name = MODE_PROPERTY, defaultValue = "java") String configuredMode,
            Instance<DataSource> dataSources
    ) {
        this(configuredMode, quarkusDataSourceSupplier(dataSources), QUARKUS_DATASOURCE_DESCRIPTION);
    }

    /** Direct wiring (tests, non-CDI use): explicit mode, datasource source, and description. */
    public GraphqlExecutionEngine(
            String configuredMode,
            Supplier<DataSource> dataSourceSupplier,
            String dataSourceDescription
    ) {
        this.mode = Mode.parse(configuredMode);
        this.dataSourceSupplier = Objects.requireNonNull(dataSourceSupplier, "dataSourceSupplier");
        this.dataSourceDescription = Objects.requireNonNull(dataSourceDescription, "dataSourceDescription");
    }

    /**
     * Non-CDI fallback used when the HTTP resource is constructed directly (unit tests,
     * plain JAX-RS): mode from the system property / environment variable, no datasource —
     * SQL mode then fails descriptively rather than ever falling back to Java mode.
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

    /** The config value form of the active mode ({@code java} | {@code sql}). */
    public String modeName() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** The runtime answering application requests under the active mode. */
    public GraphqlModelRuntime runtime() {
        if (mode == Mode.JAVA) {
            return GraphqlRuntimeRegistry.activeRuntime();
        }
        GraphqlSqlModeRuntime runtime = sqlRuntime;
        if (runtime == null) {
            synchronized (this) {
                if (sqlRuntime == null) {
                    sqlRuntime = new GraphqlSqlModeRuntime(dataSourceSupplier, dataSourceDescription);
                }
                runtime = sqlRuntime;
            }
        }
        return runtime;
    }

    /**
     * Deployment fingerprint for the mode surface: in SQL mode, the package manifest's
     * {@code artifactId} (the identity {@code titanPackage} stamped across every metadata
     * file); empty in Java mode. An unreadable artifacts directory yields the literal
     * {@code unavailable} — the serving database still answers, the surface just cannot name
     * the package.
     */
    public String fingerprint() {
        if (mode == Mode.JAVA) {
            return "";
        }
        String value = fingerprint;
        if (value == null) {
            synchronized (this) {
                if (fingerprint == null) {
                    fingerprint = readFingerprint();
                }
                value = fingerprint;
            }
        }
        return value;
    }

    private static String readFingerprint() {
        try {
            return TitanGraphqlArtifactsDirectory.readGap005Metadata().artifactId();
        } catch (RuntimeException unreadable) {
            return FINGERPRINT_UNAVAILABLE;
        }
    }

    private static Supplier<DataSource> quarkusDataSourceSupplier(Instance<DataSource> dataSources) {
        Objects.requireNonNull(dataSources, "dataSources");
        return () -> {
            if (dataSources.isUnsatisfied()) {
                throw new GraphqlSqlModeUnavailableException(
                        "SQL execution mode (" + MODE_PROPERTY + "=sql) could not answer this request:"
                                + " no Quarkus datasource bean is available"
                                + " [datasource: " + QUARKUS_DATASOURCE_DESCRIPTION + "]."
                                + " Remedy: configure quarkus.datasource.jdbc.url (plus credentials) for the"
                                + " database carrying the deployed Titan migrations, or switch "
                                + MODE_PROPERTY + " back to java.");
            }
            return dataSources.get();
        };
    }
}
