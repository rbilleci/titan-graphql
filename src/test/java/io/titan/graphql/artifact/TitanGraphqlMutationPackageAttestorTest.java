package io.titan.graphql.artifact;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.model.TitanGraphqlMutationDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TitanGraphqlMutationPackageAttestorTest {
    private static final String HANDLER = "io.titan.graphql.database.handlers.CommerceMutationProcedures.";
    private static final String ENGINE = "io.titan.graphql.database.generated.GeneratedDatabaseGraphqlSchema."
            + "executeGraphqlRequest()";
    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void acceptsOnlyReachableReviewedHandlersOnBothDialects() throws Exception {
        TitanGraphqlModelDocument model = commerce();
        for (String dialect : List.of("postgresql", "mysql")) {
            assertDoesNotThrow(() -> TitanGraphqlMutationPackageAttestor.verifyInventory(
                    model, inventory(dialect, true, true, false), dialect));
        }
    }

    @Test
    void rejectsMissingExtraAndDisconnectedHandlers() throws Exception {
        TitanGraphqlModelDocument model = commerce();
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyInventory(
                        model, inventory("postgresql", true, false, false), "postgresql"));
        IllegalStateException extra = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyInventory(
                        model, inventory("postgresql", true, true, true), "postgresql"));
        IllegalStateException disconnected = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyInventory(
                        model, inventory("postgresql", false, true, false), "postgresql"));
        assertTrue(missing.getMessage().contains("missing="));
        assertTrue(extra.getMessage().contains("extra="));
        assertTrue(disconnected.getMessage().contains("not reachable"));
    }

    @Test
    void rejectsChangedInventoryBytes() throws Exception {
        String zeros = "0000000000000000000000000000000000000000000000000000000000000000";
        String template = "{\"hashes\":{\"inventoryContentSha256\": \"" + zeros
                + "\"},\"objects\":[]}\n";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(template.getBytes(StandardCharsets.UTF_8)));
        String inventory = template.replace(zeros, hash);

        assertDoesNotThrow(() -> TitanGraphqlMutationPackageAttestor.verifyInventoryContentHash(
                inventory, hash));
        IllegalStateException changed = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyInventoryContentHash(
                        inventory.replace("\"objects\":[]", "\"objects\":[{}]"), hash));
        assertTrue(changed.getMessage().contains("does not match package bytes"));
    }

    @Test
    void verifiesPackagedSqlAgainstInventoryAndManifest() throws Exception {
        String path = "postgresql/generated__executeGraphqlRequest.sql";
        String source = "CREATE FUNCTION execute_graphql_request() RETURNS TEXT AS $$ BEGIN RETURN ''; END $$;\n";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        JsonNode object = JSON.valueToTree(Map.of("sourceInputPath", path, "sqlHash", hash));
        JsonNode manifest = JSON.valueToTree(Map.of("sourceInputs", List.of(
                Map.of("dialect", "postgresql", "path", path, "sha256", hash))));
        String script = "-- titan:source-file:generated__executeGraphqlRequest.sql\n"
                + source + "\n-- titan:source-file:next.sql\nSELECT 1;\n";

        assertDoesNotThrow(() -> TitanGraphqlMutationPackageAttestor.verifySourceSql(
                object, manifest, script, "postgresql"));
        assertDoesNotThrow(() -> TitanGraphqlMutationPackageAttestor.verifySourceSql(
                object, manifest,
                "-- titan:source-file:generated__executeGraphqlRequest.sql\n" + source + "\n",
                "postgresql"));
        IllegalStateException changed = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifySourceSql(
                        object, manifest, script.replace("RETURN '';", "RETURN 'changed';"), "postgresql"));
        assertTrue(changed.getMessage().contains("SQL bytes differ"));
    }

    @Test
    void rejectsUnreviewedDispatchAndMissingGeneratedUpdateEffect() throws Exception {
        TitanGraphqlModelDocument model = commerce();
        String sql = dispatchSql(model);
        assertDoesNotThrow(() -> TitanGraphqlMutationPackageAttestor.verifyDispatchSql(model, sql));

        IllegalStateException extra = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyDispatchSql(model,
                        sql.replace("v_mutation_root_start, 'setCustomerRating'",
                                "v_mutation_root_start, 'unreviewedMutation'")));
        IllegalStateException missingEffect = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyDispatchSql(model,
                        sql.replace("UPDATE commerce.customers SET rating = ",
                                "SELECT commerce.customers SET rating = ")));
        IllegalStateException missingColumn = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyDispatchSql(model,
                        sql.replace("UPDATE commerce.customers SET rating = ",
                                "UPDATE commerce.customers SET unrelated = ")));
        IllegalStateException missingKey = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyDispatchSql(model,
                        sql.replace("UPDATE commerce.customers SET rating = value WHERE id = value;",
                                "UPDATE commerce.customers SET rating = value WHERE unrelated = value;")));
        IllegalStateException missingPrevalidation = assertThrows(IllegalStateException.class,
                () -> TitanGraphqlMutationPackageAttestor.verifyDispatchSql(model,
                        sql.replace("v_mutation_validation_root_start, 'setCustomerRating'",
                                "v_mutation_validation_root_start, 'unreviewedMutation'")));
        assertTrue(extra.getMessage().contains("dispatch differs"));
        assertTrue(missingEffect.getMessage().contains("no modeled table effect"));
        assertTrue(missingColumn.getMessage().contains("lacks modeled column"));
        assertTrue(missingKey.getMessage().contains("lacks modeled key"));
        assertTrue(missingPrevalidation.getMessage().contains("prevalidation differs"));
    }

    private static String dispatchSql(TitanGraphqlModelDocument model) {
        StringBuilder source = new StringBuilder();
        for (TitanGraphqlMutationDocument mutation : model.mutations()) {
            source.append("IF __titan_internal_database_graphql_engine_root_field_ma_123(")
                    .append("v_query, v_request_ast, v_mutation_validation_root_start, '")
                    .append(mutation.name()).append("') THEN END IF;\n");
            source.append("IF __titan_internal_database_graphql_engine_root_field_ma_123(")
                    .append("v_query, v_request_ast, v_mutation_root_start, '")
                    .append(mutation.name()).append("') THEN\n");
            if (mutation.operation() == TitanGraphqlMutationDocument.MutationDocumentOperation.UPDATE) {
                List<String> assignments = mutation.input() == null
                        ? mutation.arguments().stream().filter(argument -> !argument.key())
                                .map(argument -> argument.column() + " = value").toList()
                        : mutation.inputBindings().stream().filter(binding -> !binding.key())
                                .map(binding -> binding.column() + " = value").toList();
                source.append("UPDATE commerce.customers SET ")
                        .append(String.join(", ", assignments)).append(" WHERE id = value;\n");
            }
            source.append("END IF;\n");
        }
        return source.toString();
    }

    private static TitanGraphqlModelDocument commerce() throws Exception {
        return TitanGraphqlModelDocumentYaml.parse(Files.readString(Path.of(
                "src/test/resources/graphql/commerce.titan.graphql.yaml")));
    }

    private static JsonNode inventory(
            String dialect,
            boolean linkNickname,
            boolean includeNickname,
            boolean includeExtra
    ) {
        List<String> dependencies = new ArrayList<>(List.of("rename"));
        if (linkNickname) {
            dependencies.add("nickname");
        }
        if (includeExtra) {
            dependencies.add("extra");
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        objects.add(object("root", dialect, dialect.equals("postgresql") ? "function" : "procedure",
                "execute_graphql_request", ENGINE, dependencies));
        objects.add(object("rename", dialect, "procedure", "rename_customer",
                HANDLER + "renameCustomer()", List.of()));
        if (includeNickname) {
            objects.add(object("nickname", dialect, "procedure", "set_nickname",
                    HANDLER + "setNickname()", List.of()));
        }
        if (includeExtra) {
            objects.add(object("extra", dialect, "procedure", "extra",
                    HANDLER + "extra()", List.of()));
        }
        return JSON.valueToTree(Map.of("objects", objects));
    }

    private static Map<String, Object> object(
            String id,
            String dialect,
            String kind,
            String name,
            String source,
            List<String> dependencies
    ) {
        return Map.of("id", id, "dialect", dialect, "kind", kind, "name", name,
                "sourceEntryPoint", source, "dependsOn", dependencies);
    }
}
