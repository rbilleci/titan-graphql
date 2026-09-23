package io.titan.graphql.codegen;

import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentity;
import io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentity;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Shared proof-client inputs for the generated database-engine public contract. */
final class DatabaseEngineTestRequestContract {

    private static final String TRANSPORT_PREFIX = "\u001eTITAN-GRAPHQL-TRANSPORT/1 ";

    private DatabaseEngineTestRequestContract() {
    }

    static String modelHash(String resource) throws IOException {
        try (InputStream input = DatabaseEngineTestRequestContract.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing model test resource " + resource);
            }
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return TitanGraphqlModelDocumentJson.semanticHash(TitanGraphqlModelDocumentYaml.parse(yaml));
        }
    }

    static String trustedContext(String actorRole) {
        return "{\"contextVersion\":\"titan.graphql.request-context/v1\",\"actorRole\":\""
                + actorRole + "\"}";
    }

    static String trustedContext(String actorRole, long deadlineEpochMillis) {
        return "{\"contextVersion\":\"titan.graphql.request-context/v1\",\"actorRole\":\""
                + actorRole + "\",\"deadlineEpochMillis\":" + deadlineEpochMillis + "}";
    }

    static String introspectionTrustedContext(String actorRole) {
        return "{\"contextVersion\":\"titan.graphql.request-context/v1\",\"actorRole\":\""
                + actorRole + "\",\"introspectionEnabled\":true}";
    }

    static String executionMetricsTrustedContext(String actorRole) {
        return "{\"contextVersion\":\"titan.graphql.request-context/v1\",\"actorRole\":\""
                + actorRole + "\",\"includeExecutionMetrics\":true}";
    }

    static String runtimeIdentity() {
        return TitanGraphqlDatabaseRuntimeIdentity.read(TitanGraphqlArtifactsDirectory.configuredDirectory());
    }

    static String packageIdentity() {
        return TitanGraphqlDatabasePackageIdentity.read(TitanGraphqlArtifactsDirectory.configuredDirectory());
    }

    /** Decodes only the fixed database transport frame used by direct PostgreSQL package tests. */
    static String postgresqlResponseJson(String framedResponse) {
        if (framedResponse == null || framedResponse.startsWith(TRANSPORT_PREFIX) == false) {
            throw new IllegalStateException("whole-request PostgreSQL function did not return a Titan transport frame");
        }
        int separator = framedResponse.indexOf('\n', TRANSPORT_PREFIX.length());
        if (separator < 0) {
            throw new IllegalStateException("whole-request PostgreSQL function returned an incomplete transport frame");
        }
        String outcome = framedResponse.substring(TRANSPORT_PREFIX.length(), separator);
        if (outcome.equals("COMMIT") == false && outcome.equals("ROLLBACK") == false) {
            throw new IllegalStateException("whole-request PostgreSQL function returned an invalid transport outcome");
        }
        return framedResponse.substring(separator + 1);
    }
}
