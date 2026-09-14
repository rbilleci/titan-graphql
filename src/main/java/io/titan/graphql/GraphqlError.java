package io.titan.graphql;

import java.util.List;

record GraphqlError(String message, List<String> path, List<Location> locations, String code) {

    GraphqlError {
        path = path == null ? List.of() : List.copyOf(path);
        locations = locations == null ? List.of() : List.copyOf(locations);
    }

    static GraphqlError of(String message, String code) {
        return new GraphqlError(message, List.of(), List.of(), code);
    }

    static GraphqlError from(GraphqlException exception) {
        List<Location> locations = exception.hasLocation()
                ? List.of(new Location(exception.line(), exception.column()))
                : List.of();
        return new GraphqlError(exception.getMessage(), exception.path(), locations, exception.code());
    }

    record Location(int line, int column) {
    }
}
