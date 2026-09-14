package io.titan.graphql;

/** Infrastructure failure while executing an installed model-generated Titan routine. */
public final class TitanCompiledGraphqlExecutionException extends RuntimeException {

    public TitanCompiledGraphqlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
