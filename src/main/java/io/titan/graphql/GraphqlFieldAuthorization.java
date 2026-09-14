package io.titan.graphql;

@FunctionalInterface
public interface GraphqlFieldAuthorization {

    GraphqlFieldAuthorization ALLOW = actorRole -> true;

    boolean canRead(String actorRole);
}
