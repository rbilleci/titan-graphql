package io.titan.graphql.consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.GraphqlSchema;
import io.titan.graphql.ProjectionField;
import io.titan.graphql.ProjectionGraphqlAdapter;
import io.titan.graphql.ProjectionModel;
import io.titan.graphql.ProjectionRetrieval;
import io.titan.graphql.ProjectionType;
import io.titan.graphql.TitanGraphqlProjection;
import java.util.List;
import org.junit.jupiter.api.Test;

class TitanGraphqlProjectionFacadeTest {

    @Test
    void externalConsumerBuildsExecutableSchemaViaPublicDescriptorPath() {
        ProjectionModel model = new ProjectionModel(
                List.of(ProjectionRetrieval.point("user", "User", "id")),
                List.of(new ProjectionType(
                        "User", "users", "public", "users", "id",
                        List.of(ProjectionField.column("id", "id"), ProjectionField.column("name", "name")),
                        List.of())));

        GraphqlSchema schema = ProjectionGraphqlAdapter.adapt(model);
        assertNotNull(schema.rootField("user"), "the public descriptor path must yield an executable schema");
        assertNotNull(schema.type("User"));
    }

    @Test
    void fluentFacadeRemainsInspectionOnlyForSdl() {
        String sdl = TitanGraphqlProjection.model()
                .pointRoot("user", "User", "id")
                .type("User").table("users")
                .scalarField("id", "id")
                .addType()
                .build()
                .schemaDefinition();
        assertTrue(sdl.contains("user"), sdl);
    }
}
