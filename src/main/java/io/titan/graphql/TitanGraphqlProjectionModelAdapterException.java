package io.titan.graphql;

final class TitanGraphqlProjectionModelAdapterException extends IllegalArgumentException {

    private final String code;

    TitanGraphqlProjectionModelAdapterException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
