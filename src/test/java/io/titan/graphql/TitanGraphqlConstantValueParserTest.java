package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TitanGraphqlConstantValueParserTest {

    @Test
    void parsesNestedModelDefaults() {
        GraphqlAst.InputObjectValue value = assertInstanceOf(GraphqlAst.InputObjectValue.class,
                TitanGraphqlConstantValueParser.parse("{state: ACTIVE, tags: [\"first\", null], enabled: true}"));
        assertEquals(new GraphqlAst.EnumValue("ACTIVE"), value.fields().get("state"));
        assertEquals(new GraphqlAst.BooleanValue(true), value.fields().get("enabled"));
        GraphqlAst.InputListValue tags = assertInstanceOf(GraphqlAst.InputListValue.class,
                value.fields().get("tags"));
        assertEquals(new GraphqlAst.StringValue("first"), tags.values().getFirst());
        assertEquals(new GraphqlAst.NullValue(), tags.values().get(1));
    }

    @Test
    void rejectsExecutableAndAmbiguousDefaultSyntax() {
        assertThrows(RuntimeException.class, () -> TitanGraphqlConstantValueParser.parse("$value"));
        assertThrows(RuntimeException.class, () -> TitanGraphqlConstantValueParser.parse("1 2"));
        assertThrows(RuntimeException.class, () -> TitanGraphqlConstantValueParser.parse("{key: 1, key: 2}"));
        assertThrows(RuntimeException.class, () -> TitanGraphqlConstantValueParser.parse("[1, 2"));
    }
}
