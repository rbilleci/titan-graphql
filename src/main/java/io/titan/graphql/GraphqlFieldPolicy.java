package io.titan.graphql;

public interface GraphqlFieldPolicy {

    GraphqlFieldPolicy ALLOW = actorRole -> true;

    boolean canRead(String actorRole);
}
