package io.titan.graphql;

record GraphqlToken(GraphqlTokenType type, String text, int offset) {
}
