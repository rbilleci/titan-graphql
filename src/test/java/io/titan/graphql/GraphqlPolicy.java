package io.titan.graphql;

public final class GraphqlPolicy {

    public boolean canReadUserEmail(String actorRole) {
        return "admin".equalsIgnoreCase(actorRole);
    }
}
