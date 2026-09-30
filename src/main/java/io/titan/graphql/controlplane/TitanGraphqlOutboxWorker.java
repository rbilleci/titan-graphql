package io.titan.graphql.controlplane;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class TitanGraphqlOutboxWorker {
    private static final Duration MAX_LEASE = Duration.ofHours(1);

    private final DataSource dataSource;
    private final Clock clock;

    public TitanGraphqlOutboxWorker(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record Event(long id, String type, String payloadJson, int attempt, String leaseToken) {
    }

    @FunctionalInterface
    public interface Delivery {
        void deliver(Event event) throws Exception;
    }

    public Optional<Event> claimNext(Duration lease) throws SQLException {
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("outbox lease must be positive and at most " + MAX_LEASE);
        }
        Instant now = clock.instant();
        return inTransaction(connection -> {
            try (PreparedStatement lookup = connection.prepareStatement(
                    "SELECT event_id, event_type, payload_json, attempt_count "
                            + "FROM public.titan_graphql_outbox "
                            + "WHERE status = 'pending' OR (status = 'leased' AND lease_until <= ?) "
                            + "ORDER BY event_id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
                lookup.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rows = lookup.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    long id = rows.getLong("event_id");
                    String type = rows.getString("event_type");
                    String payloadJson = rows.getString("payload_json");
                    int attempt = rows.getInt("attempt_count") + 1;
                    String token = UUID.randomUUID().toString();
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE public.titan_graphql_outbox "
                                    + "SET status = 'leased', attempt_count = ?, lease_token = ?, lease_until = ? "
                                    + "WHERE event_id = ?")) {
                        update.setInt(1, attempt);
                        update.setString(2, token);
                        update.setTimestamp(3, Timestamp.from(now.plus(lease)));
                        update.setLong(4, id);
                        if (update.executeUpdate() != 1) {
                            throw new SQLException("outbox claim lost its locked event");
                        }
                    }
                    return Optional.of(new Event(id, type, payloadJson, attempt, token));
                }
            }
        });
    }

    public boolean acknowledge(Event event) throws SQLException {
        Objects.requireNonNull(event, "event");
        Instant now = clock.instant();
        return inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE public.titan_graphql_outbox "
                            + "SET status = 'delivered', lease_token = NULL, lease_until = NULL, delivered_at = ? "
                            + "WHERE event_id = ? AND status = 'leased' AND lease_token = ? AND lease_until > ?")) {
                update.setTimestamp(1, Timestamp.from(now));
                update.setLong(2, event.id());
                update.setString(3, event.leaseToken());
                update.setTimestamp(4, Timestamp.from(now));
                return update.executeUpdate() == 1;
            }
        });
    }

    public boolean release(Event event) throws SQLException {
        Objects.requireNonNull(event, "event");
        return inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE public.titan_graphql_outbox "
                            + "SET status = 'pending', lease_token = NULL, lease_until = NULL "
                            + "WHERE event_id = ? AND status = 'leased' AND lease_token = ?")) {
                update.setLong(1, event.id());
                update.setString(2, event.leaseToken());
                return update.executeUpdate() == 1;
            }
        });
    }

    public boolean deliverOne(Duration lease, Delivery delivery) throws Exception {
        Objects.requireNonNull(delivery, "delivery");
        Optional<Event> claimed = claimNext(lease);
        if (claimed.isEmpty()) {
            return false;
        }
        Event event = claimed.orElseThrow();
        try {
            delivery.deliver(event);
        } catch (Exception failure) {
            try {
                release(event);
            } catch (SQLException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
        if (!acknowledge(event)) {
            throw new SQLException("outbox lease expired or was replaced before acknowledgment");
        }
        return true;
    }

    private <T> T inTransaction(SqlAction<T> action) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new SQLException("outbox worker requires an auto-commit connection");
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

    @FunctionalInterface
    private interface SqlAction<T> {
        T run(Connection connection) throws SQLException;
    }
}
