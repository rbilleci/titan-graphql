package io.titan.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/** Runtime that parses and plans generically, then reads through installed Titan carriers. */
public final class TitanCompiledGraphqlRuntime implements GraphqlModelRuntime {

    public static final String NAME = "titan-compiled";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final TitanCompiledGraphqlDataModel dataModel;
    private final String dataSourceDescription;

    public TitanCompiledGraphqlRuntime(
            TitanGraphqlModelDocument document,
            DataSource dataSource,
            String dataSourceDescription,
            TitanGraphqlGap005ArtifactMetadata packageMetadata
    ) {
        this.dataModel = new TitanCompiledGraphqlDataModel(document, dataSource, packageMetadata);
        this.dataSourceDescription = Objects.requireNonNull(dataSourceDescription, "dataSourceDescription");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute(GraphqlRuntimeRequest request, GraphqlRequestContext context) {
        try {
            return executeWithPlan(new GraphqlRequest(
                    request.query(), request.operationName(),
                    object(request.variablesJson(), "variables"),
                    object(request.extensionsJson(), "extensions")), context).json();
        } catch (TitanCompiledGraphqlExecutionException ex) {
            throw new GraphqlExecutionModeUnavailableException(
                    "Titan-compiled execution mode (titan.graphql.execution.mode=compiled) could not answer "
                            + "this request: " + ex.getMessage() + " [datasource: " + dataSourceDescription
                            + "]. Remedy: deploy the package emitted by titanGraphqlBindPackage for the exact "
                            + "reviewed model and configure the datasource for that database.", ex);
        }
    }

    @Override
    public GraphqlExecution executeWithPlan(GraphqlRequest request, GraphqlRequestContext context) {
        return GraphqlEngine.execute(dataModel, new GraphqlJsonWriter(), request, context);
    }

    private static Map<String, Object> object(String json, String field) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException ex) {
            throw new GraphqlException("request field '" + field + "' must be a JSON object");
        }
    }
}
