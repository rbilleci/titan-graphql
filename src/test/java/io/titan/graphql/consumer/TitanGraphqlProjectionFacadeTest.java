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

/**
 * Documents and locks the supported integration boundary for an external product — modelled here by a
 * package OTHER than {@code io.titan.graphql}, so only public API is visible.
 *
 * <p>Execution: the projection descriptor classes ({@link ProjectionModel}, {@link ProjectionRetrieval},
 * {@link ProjectionType}, {@link ProjectionField}) and {@link ProjectionGraphqlAdapter#adapt} are all
 * public, so a product builds a {@code ProjectionModel} and adapts it to an execution-ready
 * {@link GraphqlSchema} (then implements {@code GraphqlDataModel} for {@code GraphqlEngine}). The fluent
 * {@link TitanGraphqlProjection} facade stays deliberately inspection-only (SDL/artifact review); it is
 * not an execution entry point (see ATG-015 revisit — the schema() handle was reverted as redundant).</p>
 */
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
