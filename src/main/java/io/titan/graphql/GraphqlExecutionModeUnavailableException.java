package io.titan.graphql;

/** A configured non-Java execution mode cannot serve the request without falling back. */
public class GraphqlExecutionModeUnavailableException extends RuntimeException {

    public GraphqlExecutionModeUnavailableException(String message) {
        super(message);
    }

    public GraphqlExecutionModeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
