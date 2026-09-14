package io.titan.graphql;

public final class GraphqlJsonWriter {

    public static String error(String message) {
        return error(GraphqlError.of(message, GraphqlException.VALIDATION_ERROR));
    }

    public static String error(String message, String code) {
        return error(GraphqlError.of(message, code));
    }

    public static String error(GraphqlException exception) {
        return error(GraphqlError.from(exception));
    }

    public static String error(GraphqlError error) {
        StringBuilder json = new StringBuilder();
        json.append("{\"errors\":[{\"message\":\"").append(escape(error.message())).append('"');
        if (!error.locations().isEmpty()) {
            json.append(",\"locations\":[");
            for (int i = 0; i < error.locations().size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                GraphqlError.Location location = error.locations().get(i);
                json.append("{\"line\":").append(location.line())
                        .append(",\"column\":").append(location.column())
                        .append('}');
            }
            json.append(']');
        }
        if (!error.path().isEmpty()) {
            json.append(",\"path\":[");
            for (int i = 0; i < error.path().size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('"').append(escape(error.path().get(i))).append('"');
            }
            json.append(']');
        }
        json.append(",\"extensions\":{\"code\":\"").append(escape(error.code())).append("\"}}]}");
        return json.toString();
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
