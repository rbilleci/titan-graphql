package io.titan.graphql.database.handlers;

import io.titan.graphql.database.DatabaseGraphqlEngine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class CommerceMutationProcedures {
    private CommerceMutationProcedures() {
    }

    public static void renameCustomer(Connection connection, long id, String name) throws SQLException {
        String storedName = name + "!";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE commerce.customers SET name = ? WHERE id = ?")) {
            update.setString(1, storedName);
            update.setLong(2, id);
            update.executeUpdate();
        }
        try (PreparedStatement record = connection.prepareStatement(
                "INSERT INTO commerce.customer_changes (customer_id, new_name) VALUES (?, ?)")) {
            record.setLong(1, id);
            record.setString(2, storedName);
            record.executeUpdate();
        }
        String eventJson = "{\"customerId\":" + id + ",\"name\":"
                + DatabaseGraphqlEngine.jsonString(storedName) + "}";
        try (PreparedStatement event = connection.prepareStatement(
                "INSERT INTO public.titan_graphql_outbox (event_type, payload_json) VALUES (?, ?)")) {
            event.setString(1, "commerce.customer_renamed");
            event.setString(2, eventJson);
            event.executeUpdate();
        }
        if (name.equals("Reject after write")) {
            throw new SQLException("reviewed procedure rejected the requested name");
        }
    }

    public static void setNickname(
            Connection connection, long id, String nickname, boolean present, boolean explicitNull
    ) throws SQLException {
        if (present) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE commerce.customers SET nickname = CASE WHEN ? THEN NULL ELSE ? END WHERE id = ?")) {
                update.setBoolean(1, explicitNull);
                update.setString(2, nickname);
                update.setLong(3, id);
                update.executeUpdate();
            }
        }
    }
}
