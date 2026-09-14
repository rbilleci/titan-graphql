package io.titan.graphql;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.titan.graphql.conformance.DemoBlogSqlDeployment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test resource for {@link GraphqlSqlModeHttpIT}: provisions a PostgreSQL container (the same
 * image core's harness uses), deploys the demo DDL + the packaged Titan migrations + the
 * fixture seed (W2's deployment helper, extracted to {@link DemoBlogSqlDeployment}), and
 * points the Quarkus default datasource and {@code titan.graphql.execution.mode=sql} at it
 * BEFORE Quarkus boots — so the application under test serves {@code /graphql} from the
 * deployed stored functions.
 */
public class GraphqlSqlModeServingResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new PostgreSQLContainer<>("postgres:16");
        container.start();
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
            DemoBlogSqlDeployment.deployPackagedKernel(connection);
        } catch (Exception deploymentFailure) {
            container.stop();
            throw new IllegalStateException(
                    "failed to deploy the packaged kernel onto the serving container", deploymentFailure);
        }
        return Map.of(
                GraphqlExecutionEngine.MODE_PROPERTY, "sql",
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
}
