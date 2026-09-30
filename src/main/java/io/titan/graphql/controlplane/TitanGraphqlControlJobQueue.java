package io.titan.graphql.controlplane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class TitanGraphqlControlJobQueue {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final int MAX_JSON_LENGTH = 1_048_576;

    public enum Dialect {
        POSTGRESQL,
        MYSQL
    }

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record JobReference(String id, Status status, boolean created) {
    }

    public record ClaimedJob(String id, String type, String payloadJson, int attempt, String leaseToken) {
    }

    public record JobState(String id, String type, Status status, int attempt, String resultJson, String failureCode) {
    }

    private final DataSource dataSource;
    private final Dialect dialect;
    private final Clock clock;

    public TitanGraphqlControlJobQueue(DataSource dataSource, Dialect dialect, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public JobReference enqueue(
            Connection callerTransaction,
            String type,
            String requestKey,
            String payloadJson
    ) throws SQLException {
        Objects.requireNonNull(callerTransaction, "callerTransaction");
        if (callerTransaction.getAutoCommit()) {
            throw new SQLException("control-plane job enqueue requires a caller-owned transaction");
        }
        String jobType = requireType(type);
        String key = requestKey == null || requestKey.isBlank() ? null : requestKey;
        if (key != null && key.length() > 128) {
            throw new IllegalArgumentException("control-plane job request key exceeds 128 characters");
        }
        String payload = requireJsonObject(payloadJson, "payload");
        String payloadHash = sha256(payload);
        String candidateId = UUID.randomUUID().toString();
        String insertSql = "INSERT INTO public.titan_graphql_control_jobs "
                + "(job_id, job_type, request_key, payload_json, payload_sha256, status, attempt_count) "
                + "VALUES (?, ?, ?, ?, ?, 'pending', 0) "
                + (dialect == Dialect.POSTGRESQL
                        ? "ON CONFLICT (job_type, request_key) DO NOTHING"
                        : "ON DUPLICATE KEY UPDATE job_id = job_id");
        try (PreparedStatement insert = callerTransaction.prepareStatement(insertSql)) {
            insert.setString(1, candidateId);
            insert.setString(2, jobType);
            if (key == null) {
                insert.setNull(3, Types.VARCHAR);
            } else {
                insert.setString(3, key);
            }
            insert.setString(4, payload);
            insert.setString(5, payloadHash);
            insert.executeUpdate();
        }
        String lookupSql = key == null
                ? "SELECT job_id, payload_sha256, status FROM public.titan_graphql_control_jobs WHERE job_id = ?"
                : "SELECT job_id, payload_sha256, status FROM public.titan_graphql_control_jobs "
                        + "WHERE job_type = ? AND request_key = ?";
        try (PreparedStatement lookup = callerTransaction.prepareStatement(lookupSql)) {
            if (key == null) {
                lookup.setString(1, candidateId);
            } else {
                lookup.setString(1, jobType);
                lookup.setString(2, key);
            }
            try (ResultSet rows = lookup.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("control-plane job insert has no visible row");
                }
                if (!payloadHash.equals(rows.getString("payload_sha256"))) {
                    throw new IllegalArgumentException("control-plane job request key conflicts with another payload");
                }
                String id = rows.getString("job_id");
                return new JobReference(id, status(rows.getString("status")), candidateId.equals(id));
            }
        }
    }

    public Optional<ClaimedJob> claimNext(Duration lease) throws SQLException {
        return claimNextMatching(null, lease);
    }

    public Optional<ClaimedJob> claimNext(String type, Duration lease) throws SQLException {
        return claimNextMatching(requireType(type), lease);
    }

    private Optional<ClaimedJob> claimNextMatching(String type, Duration lease) throws SQLException {
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("control-plane job lease must be positive and at most " + MAX_LEASE);
        }
        Instant now = clock.instant();
        return inTransaction(connection -> {
            try (PreparedStatement lookup = connection.prepareStatement(
                    "SELECT job_id, job_type, payload_json, attempt_count "
                            + "FROM public.titan_graphql_control_jobs "
                            + "WHERE " + (type == null ? "" : "job_type = ? AND ")
                            + "(status = 'pending' OR (status = 'running' AND lease_until <= ?)) "
                            + "ORDER BY created_at, job_id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
                if (type == null) {
                    lookup.setTimestamp(1, Timestamp.from(now));
                } else {
                    lookup.setString(1, type);
                    lookup.setTimestamp(2, Timestamp.from(now));
                }
                try (ResultSet rows = lookup.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    String id = rows.getString("job_id");
                    int attempt = rows.getInt("attempt_count") + 1;
                    String token = UUID.randomUUID().toString();
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE public.titan_graphql_control_jobs "
                                    + "SET status = 'running', attempt_count = ?, lease_token = ?, "
                                    + "lease_until = ?, failure_code = NULL WHERE job_id = ?")) {
                        update.setInt(1, attempt);
                        update.setString(2, token);
                        update.setTimestamp(3, Timestamp.from(now.plus(lease)));
                        update.setString(4, id);
                        if (update.executeUpdate() != 1) {
                            throw new SQLException("control-plane job claim lost its locked row");
                        }
                    }
                    return Optional.of(new ClaimedJob(
                            id, rows.getString("job_type"), rows.getString("payload_json"), attempt, token));
                }
            }
        });
    }

    public boolean complete(ClaimedJob job, String resultJson) throws SQLException {
        Objects.requireNonNull(job, "job");
        String result = requireJsonObject(resultJson, "result");
        Instant now = clock.instant();
        return updateClaim(job, "succeeded", result, null, now);
    }

    public boolean renew(ClaimedJob job, Duration lease) throws SQLException {
        Objects.requireNonNull(job, "job");
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("control-plane job lease must be positive and at most " + MAX_LEASE);
        }
        Instant now = clock.instant();
        return inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE public.titan_graphql_control_jobs SET lease_until = ? "
                            + "WHERE job_id = ? AND status = 'running' "
                            + "AND lease_token = ? AND lease_until > ?")) {
                update.setTimestamp(1, Timestamp.from(now.plus(lease)));
                update.setString(2, job.id());
                update.setString(3, job.leaseToken());
                update.setTimestamp(4, Timestamp.from(now));
                return update.executeUpdate() == 1;
            }
        });
    }

    public boolean complete(Connection callerTransaction, ClaimedJob job, String resultJson) throws SQLException {
        Objects.requireNonNull(callerTransaction, "callerTransaction");
        if (callerTransaction.getAutoCommit()) {
            throw new SQLException("control-plane job completion requires a caller-owned transaction");
        }
        Objects.requireNonNull(job, "job");
        String result = requireJsonObject(resultJson, "result");
        return updateClaim(callerTransaction, job, "succeeded", result, null, clock.instant());
    }

    public boolean retry(ClaimedJob job, String failureCode) throws SQLException {
        Objects.requireNonNull(job, "job");
        return updateClaim(job, "pending", null, requireFailureCode(failureCode), null);
    }

    public boolean fail(ClaimedJob job, String failureCode) throws SQLException {
        Objects.requireNonNull(job, "job");
        return updateClaim(job, "failed", null, requireFailureCode(failureCode), clock.instant());
    }

    public Optional<JobState> find(String jobId) throws SQLException {
        Objects.requireNonNull(jobId, "jobId");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement lookup = connection.prepareStatement(
                        "SELECT job_id, job_type, status, attempt_count, result_json, failure_code "
                                + "FROM public.titan_graphql_control_jobs WHERE job_id = ?")) {
            lookup.setString(1, jobId);
            try (ResultSet rows = lookup.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new JobState(
                        rows.getString("job_id"),
                        rows.getString("job_type"),
                        status(rows.getString("status")),
                        rows.getInt("attempt_count"),
                        rows.getString("result_json"),
                        rows.getString("failure_code")));
            }
        }
    }

    private boolean updateClaim(
            ClaimedJob job,
            String nextStatus,
            String resultJson,
            String failureCode,
            Instant finishedAt
    ) throws SQLException {
        return inTransaction(connection -> updateClaim(
                connection, job, nextStatus, resultJson, failureCode, finishedAt));
    }

    private boolean updateClaim(
            Connection connection,
            ClaimedJob job,
            String nextStatus,
            String resultJson,
            String failureCode,
            Instant finishedAt
    ) throws SQLException {
        Instant now = clock.instant();
        try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE public.titan_graphql_control_jobs "
                            + "SET status = ?, lease_token = NULL, lease_until = NULL, "
                            + "result_json = ?, failure_code = ?, finished_at = ? "
                            + "WHERE job_id = ? AND status = 'running' "
                            + "AND lease_token = ? AND lease_until > ?")) {
            update.setString(1, nextStatus);
            if (resultJson == null) update.setNull(2, Types.LONGVARCHAR);
            else update.setString(2, resultJson);
            if (failureCode == null) update.setNull(3, Types.VARCHAR);
            else update.setString(3, failureCode);
            if (finishedAt == null) update.setNull(4, Types.TIMESTAMP);
            else update.setTimestamp(4, Timestamp.from(finishedAt));
            update.setString(5, job.id());
            update.setString(6, job.leaseToken());
            update.setTimestamp(7, Timestamp.from(now));
            return update.executeUpdate() == 1;
        }
    }

    private <T> T inTransaction(SqlAction<T> action) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new SQLException("control-plane job worker requires an auto-commit connection");
            }
            connection.setAutoCommit(false);
            try {
                T value = action.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static Status status(String value) {
        return Status.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    private static String requireType(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("control-plane job type is invalid");
        }
        return value;
    }

    private static String requireFailureCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("control-plane job failure code is invalid");
        }
        return value;
    }

    private static String requireJsonObject(String value, String field) {
        if (value == null || value.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("control-plane job " + field + " exceeds its JSON bound");
        }
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("control-plane job " + field + " must be a JSON object");
            }
            return value;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("control-plane job " + field + " must be valid JSON", failure);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        T run(Connection connection) throws SQLException;
    }
}
