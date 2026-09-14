package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GraphqlCursorCodecTest {

    @Test
    void encodesAndDecodesRootCursorPayloads() {
        GraphqlRootField.RootCursorOrdering ordering = new GraphqlRootField.RootCursorOrdering(
                "publishedAt",
                "published_at",
                "publishedAt",
                GraphqlRootField.RootCursorDirection.DESC,
                "id"
        );
        GraphqlCursorCodec.CursorPayload payload = GraphqlCursorCodec.payload(
                ordering,
                "2026-05-30T00:00:00Z",
                "42"
        );

        String cursor = GraphqlCursorCodec.encode(payload);
        GraphqlCursorCodec.CursorPayload decoded = GraphqlCursorCodec.decodeForOrdering(cursor, ordering);

        assertEquals("publishedAt", decoded.orderingName());
        assertEquals("publishedAt", decoded.cursorPath());
        assertEquals(GraphqlRootField.RootCursorDirection.DESC, decoded.direction());
        assertEquals("2026-05-30T00:00:00Z", decoded.value());
        assertEquals("id", decoded.tieBreakerColumnName());
        assertEquals("42", decoded.tieBreakerValue());
    }
}
