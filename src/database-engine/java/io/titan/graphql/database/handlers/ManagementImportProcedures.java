package io.titan.graphql.database.handlers;

import io.titan.graphql.database.DatabaseGraphqlEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class ManagementImportProcedures {
    private ManagementImportProcedures() {
    }

    public static void requestModelImport(
            Connection connection,
            String id,
            String workspaceId,
            String yaml,
            String trustedContextJson
    ) throws SQLException {
        String actorRole = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "actorRole");
        String actorKey = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "actorKey");
        String requestId = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "requestId");
        String idempotencyKey = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "idempotencyKey");
        if (!DatabaseGraphqlEngine.uuidTextIsValid(id)
                || workspaceId == null || workspaceId.length() == 0 || workspaceId.length() > 191
                || yaml == null || yaml.length() == 0 || yaml.length() > 16_000
                || actorRole.length() == 0 || actorRole.length() > 32
                || actorKey.length() == 0 || actorKey.length() > 191
                || requestId.length() == 0 || requestId.length() > 128
                || idempotencyKey.length() == 0 || idempotencyKey.length() > 128) {
            throw new SQLException("model import request fields or trusted context are invalid");
        }
        String payload = "{\"workspaceId\":" + DatabaseGraphqlEngine.jsonString(workspaceId)
                + ",\"yaml\":" + DatabaseGraphqlEngine.jsonString(yaml)
                + ",\"actorRole\":" + DatabaseGraphqlEngine.jsonString(actorRole)
                + ",\"actorKey\":" + DatabaseGraphqlEngine.jsonString(actorKey)
                + ",\"requestId\":" + DatabaseGraphqlEngine.jsonString(requestId)
                + ",\"idempotencyKey\":" + DatabaseGraphqlEngine.jsonString(idempotencyKey) + "}";
        try (PreparedStatement request = connection.prepareStatement(
                "INSERT INTO management.graphql_import_requests "
                        + "(job_id, workspace_id, yaml_source) VALUES (?, ?, ?)")) {
            request.setString(1, id);
            request.setString(2, workspaceId);
            request.setString(3, yaml);
            request.executeUpdate();
        }
        String hash = "";
        try (PreparedStatement digest = connection.prepareStatement(
                "SELECT management_graphql.titan_graphql_job_payload_sha256(?) AS payload_sha256 "
                        + "FROM management.graphql_import_requests WHERE job_id = ?")) {
            digest.setString(1, payload);
            digest.setString(2, id);
            try (ResultSet rows = digest.executeQuery()) {
                if (rows.next()) {
                    hash = rows.getString("payload_sha256");
                }
            }
        }
        if (hash.length() != 64) {
            throw new SQLException("model import payload hash failed");
        }
        try (PreparedStatement job = connection.prepareStatement(
                "INSERT INTO public.titan_graphql_control_jobs "
                        + "(job_id, job_type, request_key, payload_json, payload_sha256, status, attempt_count) "
                        + "VALUES (?, ?, ?, ?, ?, 'pending', 0)")) {
            job.setString(1, id);
            job.setString(2, "model.import");
            job.setString(3, id);
            job.setString(4, payload);
            job.setString(5, hash);
            job.executeUpdate();
        }
    }
}
