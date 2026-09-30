package io.titan.graphql.codegen;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class CommerceMutationRegistryContract {
    private CommerceMutationRegistryContract() {
    }

    static Set<String> declaredNames() throws IOException {
        return model().mutations().stream()
                .map(mutation -> mutation.name())
                .collect(Collectors.toSet());
    }

    static String expectedIdentity() throws IOException {
        return TitanGraphqlModelDocumentJson.mutationRegistryHash(model());
    }

    static String installedIdentity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT public.mutation_registry_identity()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("installed mutation registry identity returned no row");
            }
            return result.getString(1);
        }
    }

    private static TitanGraphqlModelDocument model() throws IOException {
        try (InputStream input = CommerceMutationRegistryContract.class.getResourceAsStream(
                "/graphql/commerce.titan.graphql.yaml")) {
            if (input == null) {
                throw new IOException("Commerce model test resource is missing");
            }
            return TitanGraphqlModelDocumentYaml.parse(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static Map<String, String> requests() {
        return Map.ofEntries(
                Map.entry("renameCustomer",
                        "mutation { renameCustomer(id: 7, name: \"Registry update\") { id name } }"),
                Map.entry("renameCustomerWithProcedure",
                        "mutation { renameCustomerWithProcedure(id: 7, name: \"Registry procedure\") { id name } }"),
                Map.entry("renameCustomerWithInput",
                        "mutation { renameCustomerWithInput(input: {id: 7, patch: {name: \"Registry input\"}}) { id name } }"),
                Map.entry("setCustomerNickname",
                        "mutation { setCustomerNickname(input: {id: 7, patch: {nickname: \"Registry\"}}) { id nickname } }"),
                Map.entry("setCustomerNicknameFlat",
                        "mutation { setCustomerNicknameFlat(id: 7, nickname: \"Registry\") { id nickname } }"),
                Map.entry("setCustomerNicknameWithProcedure",
                        "mutation { setCustomerNicknameWithProcedure(id: 7, nickname: \"Registry\") { id nickname } }"),
                Map.entry("setCustomerNicknameWithInputProcedure",
                        "mutation { setCustomerNicknameWithInputProcedure(input: {id: 7, patch: {nickname: \"Registry\"}}) { id nickname } }"),
                Map.entry("setCustomerActive",
                        "mutation { setCustomerActive(id: 7, active: true) { id active } }"),
                Map.entry("setCustomerRating",
                        "mutation { setCustomerRating(id: 7, rating: 7) { id rating } }"),
                Map.entry("setCustomerVerified",
                        "mutation { setCustomerVerified(id: 7, verified: true) { id verified } }"),
                Map.entry("setCustomerRebate",
                        "mutation { setCustomerRebate(id: 7, rebate: 1.5) { id rebate } }"),
                Map.entry("setCustomerOptionalStatus",
                        "mutation { setCustomerOptionalStatus(id: 7, optionalStatus: ACTIVE) { id optionalStatus } }"),
                Map.entry("setCustomerCreditLimit",
                        "mutation { setCustomerCreditLimit(id: 7, creditLimit: 100.25) { id creditLimit } }"),
                Map.entry("setCustomerStatus",
                        "mutation { setCustomerStatus(id: 7, status: ACTIVE) { id status } }")
        );
    }
}
