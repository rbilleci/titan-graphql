package io.titan.graphql;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.titan.graphql.conformance.DemoBlogSqlDeployment;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots Quarkus against a fresh PostgreSQL database carrying only the generated,
 * manifest-bound whole-request engine package. It deliberately does not deploy the historical
 * SQL-mode kernel, so an HTTP success proves the database-mode configuration and entry point.
 */
public class GraphqlDatabaseEngineServingResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new PostgreSQLContainer<>("postgres:16");
        container.start();
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
            DemoBlogSqlDeployment.deployPackagedDatabaseEngine(connection);
        } catch (Exception deploymentFailure) {
            container.stop();
            throw new IllegalStateException(
                    "failed to deploy the generated database GraphQL engine onto the serving container",
                    deploymentFailure);
        }
        return Map.of(
                GraphqlExecutionEngine.MODE_PROPERTY, "database",
                GraphqlExecutionEngine.DATABASE_ENGINE_DIALECT_PROPERTY, "postgresql",
                GraphqlExecutionEngine.MODEL_PATH_PROPERTY,
                Path.of("src/test/resources/graphql/demo-blog.titan.graphql.yaml").toAbsolutePath().toString(),
                "titan.graphql.artifacts.dir", requiredSystemProperty("titan.graphql.artifacts.dir"),
                "quarkus.datasource.jdbc.url", container.getJdbcUrl(),
                "quarkus.datasource.username", container.getUsername(),
                "quarkus.datasource.password", container.getPassword());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    private static String requiredSystemProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("database HTTP test requires system property " + name);
        }
        return value;
    }
}
