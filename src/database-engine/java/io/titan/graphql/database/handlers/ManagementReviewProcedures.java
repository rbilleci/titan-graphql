package io.titan.graphql.database.handlers;

import io.titan.graphql.database.DatabaseGraphqlEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class ManagementReviewProcedures {
    private ManagementReviewProcedures() {
    }

    public static void requestObservedOperationReview(
            Connection connection,
            String decision,
            String id,
            String observedOperationId,
            String trustedContextJson
    ) throws SQLException {
        String actorRole = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "actorRole");
        String actorKey = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "actorKey");
        String requestId = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "requestId");
        String idempotencyKey = DatabaseGraphqlEngine.trustedContextString(trustedContextJson, "idempotencyKey");
        if (!DatabaseGraphqlEngine.uuidTextIsValid(id)
                || observedOperationId == null || observedOperationId.length() == 0
                || observedOperationId.length() > 64
                || (!"approve".equals(decision) && !"reject".equals(decision))
                || actorRole.length() == 0 || actorRole.length() > 32
                || actorKey.length() == 0 || actorKey.length() > 191
                || requestId.length() == 0 || requestId.length() > 128
                || idempotencyKey.length() == 0 || idempotencyKey.length() > 128) {
            throw new SQLException("operation review request fields or trusted context are invalid");
        }
        long observedCount = 0L;
        try (PreparedStatement observed = connection.prepareStatement(
                "SELECT COUNT(*) AS observed_count FROM management.graphql_observed_operations WHERE id = ?")) {
            observed.setString(1, observedOperationId);
            try (ResultSet rows = observed.executeQuery()) {
                if (rows.next()) {
                    observedCount = rows.getLong("observed_count");
                }
            }
        }
        if (observedCount != 1L) {
            throw new SQLException("observed operation does not exist");
        }
        String payload = "{\"observedOperationId\":" + DatabaseGraphqlEngine.jsonString(observedOperationId)
                + ",\"decision\":" + DatabaseGraphqlEngine.jsonString(decision)
                + ",\"reviewedBy\":" + DatabaseGraphqlEngine.jsonString(actorKey)
                + ",\"actorRole\":" + DatabaseGraphqlEngine.jsonString(actorRole)
                + ",\"requestId\":" + DatabaseGraphqlEngine.jsonString(requestId)
                + ",\"idempotencyKey\":" + DatabaseGraphqlEngine.jsonString(idempotencyKey) + "}";
        String hash = "";
        try (PreparedStatement digest = connection.prepareStatement(
                "SELECT management_graphql.titan_graphql_job_payload_sha256(?) AS payload_sha256 "
                        + "FROM management.graphql_observed_operations WHERE id = ?")) {
            digest.setString(1, payload);
            digest.setString(2, observedOperationId);
            try (ResultSet rows = digest.executeQuery()) {
                if (rows.next()) {
                    hash = rows.getString("payload_sha256");
                }
            }
        }
        if (hash.length() != 64) {
            throw new SQLException("operation review payload hash failed");
        }
        try (PreparedStatement request = connection.prepareStatement(
                "INSERT INTO management.graphql_review_requests "
                        + "(job_id, observed_operation_id, decision) VALUES (?, ?, ?)")) {
            request.setString(1, id);
            request.setString(2, observedOperationId);
            request.setString(3, decision);
            request.executeUpdate();
        }
        try (PreparedStatement job = connection.prepareStatement(
                "INSERT INTO public.titan_graphql_control_jobs "
                        + "(job_id, job_type, request_key, payload_json, payload_sha256, status, attempt_count) "
                        + "VALUES (?, ?, ?, ?, ?, 'pending', 0)")) {
            job.setString(1, id);
            job.setString(2, "operation.review");
            job.setString(3, id);
            job.setString(4, payload);
            job.setString(5, hash);
            job.executeUpdate();
        }
    }
}
