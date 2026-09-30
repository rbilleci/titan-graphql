package io.titan.graphql.database.handlers;

import io.titan.graphql.database.DatabaseGraphqlEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class ManagementValidationProcedures {
    private ManagementValidationProcedures() {
    }

    public static void requestModelValidation(Connection connection, String draftId, String id)
            throws SQLException {
        if (!DatabaseGraphqlEngine.uuidTextIsValid(id)
                || draftId == null || draftId.length() == 0 || draftId.length() > 191) {
            throw new SQLException("model validation request fields are invalid");
        }
        long draftCount = 0L;
        try (PreparedStatement draft = connection.prepareStatement(
                "SELECT COUNT(*) AS draft_count FROM management.management_drafts WHERE id = ?")) {
            draft.setString(1, draftId);
            try (ResultSet rows = draft.executeQuery()) {
                if (rows.next()) {
                    draftCount = rows.getLong("draft_count");
                }
            }
        }
        if (draftCount == 0L) {
            throw new SQLException("model validation draft does not exist");
        }
        String payload = "{\"draftId\":" + DatabaseGraphqlEngine.jsonString(draftId) + "}";
        String hash = "";
        try (PreparedStatement digest = connection.prepareStatement(
                "SELECT management_graphql.titan_graphql_job_payload_sha256(?) AS payload_sha256 "
                        + "FROM management.management_drafts WHERE id = ?")) {
            digest.setString(1, payload);
            digest.setString(2, draftId);
            try (ResultSet rows = digest.executeQuery()) {
                if (rows.next()) {
                    hash = rows.getString("payload_sha256");
                }
            }
        }
        if (hash.length() != 64) {
            throw new SQLException("model validation payload hash failed");
        }
        try (PreparedStatement request = connection.prepareStatement(
                "INSERT INTO management.graphql_validation_requests (job_id, draft_id) VALUES (?, ?)")) {
            request.setString(1, id);
            request.setString(2, draftId);
            request.executeUpdate();
        }
        try (PreparedStatement job = connection.prepareStatement(
                "INSERT INTO public.titan_graphql_control_jobs "
                        + "(job_id, job_type, request_key, payload_json, payload_sha256, status, attempt_count) "
                        + "VALUES (?, ?, ?, ?, ?, 'pending', 0)")) {
            job.setString(1, id);
            job.setString(2, "model.validate");
            job.setString(3, id);
            job.setString(4, payload);
            job.setString(5, hash);
            job.executeUpdate();
        }
    }
}
