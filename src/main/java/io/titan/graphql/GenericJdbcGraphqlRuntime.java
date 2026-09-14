package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.validation.TitanGraphqlModelDocumentValidator;
import io.titan.graphql.validation.TitanGraphqlValidationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javax.sql.DataSource;

/** Runtime assembled from a model document and a datasource, with no model-specific Java executor. */
public final class GenericJdbcGraphqlRuntime implements GraphqlModelRuntime {

    public static final String NAME = "generic-jdbc";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<java.util.Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final GenericJdbcGraphqlDataModel dataModel;

    public GenericJdbcGraphqlRuntime(TitanGraphqlModelDocument document, DataSource dataSource) {
        Objects.requireNonNull(document, "document");
        TitanGraphqlValidationReport report = TitanGraphqlModelDocumentValidator.validate(document);
        if (report.blocksDeployment()) {
            throw new IllegalArgumentException("GraphQL model document has " + report.errorCount()
                    + " deployment-blocking validation error(s)");
        }
        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(
                TitanGraphqlProjectionModelAdapter.adapt(document));
        this.dataModel = new GenericJdbcGraphqlDataModel(schema, dataSource);
    }

    public static GenericJdbcGraphqlRuntime fromYaml(Path path, DataSource dataSource) {
        try {
            return new GenericJdbcGraphqlRuntime(
                    TitanGraphqlModelDocumentYaml.parse(Files.readString(path)), dataSource);
        } catch (IOException ex) {
            throw new IllegalStateException("could not read GraphQL model document " + path.toAbsolutePath(), ex);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute(GraphqlRuntimeRequest request, GraphqlRequestContext context) {
        try {
            return executeWithPlan(new GraphqlRequest(
                    request.query(),
                    request.operationName(),
                    object(request.variablesJson(), "variables"),
                    object(request.extensionsJson(), "extensions")
            ), context).json();
        } catch (GraphqlExecutionModeUnavailableException ex) {
            throw ex;
        } catch (io.titan.runtime.jdbc.JdbcExecutionException ex) {
            throw new GraphqlExecutionModeUnavailableException(
                    "JDBC execution mode (titan.graphql.execution.mode=jdbc) could not execute the "
                            + "planned Titan DSL read (" + ex.getMessage() + "). Verify datasource access and "
                            + "the reviewed model's physical table and column bindings.", ex);
        }
    }

    @Override
    public GraphqlExecution executeWithPlan(GraphqlRequest request, GraphqlRequestContext context) {
        return GraphqlEngine.execute(dataModel, new GraphqlJsonWriter(), request, context);
    }

    private static java.util.Map<String, Object> object(String json, String field) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return JSON.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException ex) {
            throw new GraphqlException("request field '" + field + "' must be a JSON object");
        }
    }
}
