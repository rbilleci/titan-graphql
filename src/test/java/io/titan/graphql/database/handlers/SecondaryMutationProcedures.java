package io.titan.graphql.database.handlers;

import java.sql.Connection;

public final class SecondaryMutationProcedures {
    private SecondaryMutationProcedures() {
    }

    public static void renameCustomer(Connection connection, long id, String name) {
    }

    public static void setNickname(
            Connection connection, long id, String nickname, boolean present, boolean explicitNull
    ) {
    }
}
