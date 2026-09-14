package io.titan.graphql;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class GraphqlCursorCodec {

    private static final String VERSION = "tgqlc1";

    private GraphqlCursorCodec() {
    }

    public record CursorPayload(
            String orderingName,
            String cursorPath,
            GraphqlRootField.RootCursorDirection direction,
            String value,
            String tieBreakerColumnName,
            String tieBreakerValue
    ) {
    }

    public static CursorPayload payload(
            GraphqlRootField.RootCursorOrdering ordering,
            String value,
            String tieBreakerValue
    ) {
        return new CursorPayload(
                ordering.name(),
                ordering.cursorPath(),
                ordering.direction(),
                value,
                ordering.tieBreakerColumnName(),
                tieBreakerValue
        );
    }

    public static CursorPayload payload(
            GraphqlSelection.RootOrder ordering,
            String value,
            String tieBreakerValue
    ) {
        return new CursorPayload(
                ordering.name(),
                ordering.sortPath(),
                ordering.direction(),
                value,
                ordering.tieBreakerColumnName(),
                tieBreakerValue
        );
    }

    public static CursorPayload payload(
            GraphqlFieldDescriptor.RelationSortPath ordering,
            String value,
            String tieBreakerValue
    ) {
        return new CursorPayload(
                ordering.name(),
                ordering.sortPath(),
                relationDirection(ordering.direction()),
                value,
                ordering.tieBreakerColumnName(),
                tieBreakerValue
        );
    }

    public static String encode(CursorPayload payload) {
        return VERSION
                + "." + encodePart(payload.orderingName())
                + "." + encodePart(payload.cursorPath())
                + "." + encodePart(payload.direction().name())
                + "." + encodePart(payload.value())
                + "." + encodePart(payload.tieBreakerColumnName())
                + "." + encodePart(payload.tieBreakerValue());
    }

    public static CursorPayload decode(String cursor) {
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 7 || parts[0].equals(VERSION) == false) {
            throw new GraphqlException("invalid Relay cursor");
        }
        try {
            return new CursorPayload(
                    decodePart(parts[1]),
                    decodePart(parts[2]),
                    GraphqlRootField.RootCursorDirection.valueOf(decodePart(parts[3])),
                    decodePart(parts[4]),
                    decodePart(parts[5]),
                    decodePart(parts[6])
            );
        } catch (IllegalArgumentException exception) {
            throw new GraphqlException("invalid Relay cursor");
        }
    }

    static CursorPayload decodeForOrdering(
            String cursor,
            GraphqlRootField.RootCursorOrdering ordering
    ) {
        CursorPayload payload = decode(cursor);
        if (payload.orderingName().equals(ordering.name()) == false
                || payload.cursorPath().equals(ordering.cursorPath()) == false
                || payload.direction() != ordering.direction()
                || payload.tieBreakerColumnName().equals(ordering.tieBreakerColumnName()) == false) {
            throw new GraphqlException("Relay cursor does not match root cursor ordering");
        }
        return payload;
    }

    static CursorPayload decodeForOrdering(
            String cursor,
            GraphqlSelection.RootOrder ordering
    ) {
        CursorPayload payload = decode(cursor);
        if (payload.orderingName().equals(ordering.name()) == false
                || payload.cursorPath().equals(ordering.sortPath()) == false
                || payload.direction() != ordering.direction()
                || payload.tieBreakerColumnName().equals(ordering.tieBreakerColumnName()) == false) {
            throw new GraphqlException("Relay cursor does not match generated root ordering");
        }
        return payload;
    }

    static CursorPayload decodeForOrdering(
            String cursor,
            GraphqlFieldDescriptor.RelationSortPath ordering
    ) {
        CursorPayload payload = decode(cursor);
        if (payload.orderingName().equals(ordering.name()) == false
                || payload.cursorPath().equals(ordering.sortPath()) == false
                || payload.direction() != relationDirection(ordering.direction())
                || payload.tieBreakerColumnName().equals(ordering.tieBreakerColumnName()) == false) {
            throw new GraphqlException("Relay cursor does not match relation cursor ordering");
        }
        return payload;
    }

    private static GraphqlRootField.RootCursorDirection relationDirection(
            GraphqlFieldDescriptor.RelationSortDirection direction
    ) {
        return switch (direction) {
            case ASC -> GraphqlRootField.RootCursorDirection.ASC;
            case DESC -> GraphqlRootField.RootCursorDirection.DESC;
        };
    }

    private static String encodePart(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
