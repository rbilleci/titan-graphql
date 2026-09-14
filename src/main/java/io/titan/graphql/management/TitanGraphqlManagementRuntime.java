package io.titan.graphql.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.titan.graphql.GraphqlEngine;
import io.titan.graphql.GraphqlExecution;
import io.titan.graphql.GraphqlJsonWriter;
import io.titan.graphql.GraphqlManagementDataModel;
import io.titan.graphql.GraphqlModelRuntime;
import io.titan.graphql.GraphqlRequest;
import io.titan.graphql.GraphqlRequestContext;
import io.titan.graphql.GraphqlRuntimeRequest;
import java.util.Map;

public final class TitanGraphqlManagementRuntime implements GraphqlModelRuntime {

    public static final String NAME = "management";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final GraphqlManagementDataModel dataModel = new GraphqlManagementDataModel();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String execute(GraphqlRuntimeRequest request, GraphqlRequestContext context) {
        try {
            return executeWithPlan(
                    new GraphqlRequest(
                            request.query(),
                            request.operationName(),
                            readJsonObject(request.variablesJson(), "variables"),
                            readJsonObject(request.extensionsJson(), "extensions")
                    ),
                    context
            ).json();
        } catch (IllegalArgumentException ex) {
            return GraphqlJsonWriter.error(ex.getMessage());
        }
    }

    @Override
    public GraphqlExecution executeWithPlan(GraphqlRequest request, GraphqlRequestContext context) {
        GraphqlJsonWriter jsonWriter = new GraphqlJsonWriter();
        return GraphqlEngine.execute(dataModel, jsonWriter, request, context);
    }

    private static Map<String, Object> readJsonObject(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, JSON_OBJECT);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("request field '" + fieldName + "' must be a JSON object", ex);
        }
    }
}
