package io.titan.graphql.database;

/**
 * Bounded scalar representation of one GraphQL type reference.
 *
 * <p>The carrier is postfix so nested lists and non-null wrappers can be created with one
 * deterministic source scan: {@code Int} is {@code tr1;N:Int;}, {@code Int!} is
 * {@code tr1;N:Int;!;}, and {@code [Int!]!} is {@code tr1;N:Int;!;L;!;}. It is deliberately a
 * language-core descriptor, not schema metadata; generated schema descriptors decide whether the
 * final named type is a permitted input or output type.</p>
 */
public final class DatabaseGraphqlTypeReference {

    private static final String PREFIX = "tr1;";
    private static final int MAX_LIST_DEPTH = 128;

    private DatabaseGraphqlTypeReference() {
    }

    /** Parses a bounded GraphQL type-reference source range into the scalar descriptor. */
    public static String parse(String typeText) {
        if (typeText == null || typeText.length() == 0) {
            return "";
        }
        String descriptor = PREFIX;
        String name = "";
        boolean named = false;
        boolean previousWasNonNull = false;
        int listDepth = 0;
        int position = 0;
        boolean inComment = false;
        while (position < typeText.length()) {
            char current = typeText.charAt(position);
            // AST ranges end at the following token boundary, so they can retain trailing
            // GraphQL ignored text.  Accept the language's ignorable separators here instead
            // of asking the old lexical-plan carrier to normalize the same range again.
            boolean ignored = inComment || current == ' ' || current == '\t' || current == '\n'
                    || current == '\r' || current == ',' || current == '#';
            if (inComment && (current == '\n' || current == '\r')) {
                inComment = false;
            } else if (current == '#') {
                inComment = true;
            }
            if (!ignored) {
                boolean nameStart = current >= 'A' && current <= 'Z'
                        || current >= 'a' && current <= 'z' || current == '_';
                boolean namePart = nameStart || current >= '0' && current <= '9';
                if (!named && namePart) {
                    if (name.length() == 0 && !nameStart) {
                        return "";
                    }
                    name = name + current;
                    previousWasNonNull = false;
                } else {
                    if (!named && name.length() != 0) {
                        descriptor = descriptor + "N:" + name + ";";
                        named = true;
                    }
                    if (current == '[') {
                        if (named || name.length() != 0) {
                            return "";
                        }
                        listDepth++;
                        if (listDepth > MAX_LIST_DEPTH) {
                            return "";
                        }
                    } else if (current == ']') {
                        if (!named || listDepth == 0) {
                            return "";
                        }
                        descriptor = descriptor + "L;";
                        listDepth--;
                        previousWasNonNull = false;
                    } else if (current == '!') {
                        if (!named || previousWasNonNull) {
                            return "";
                        }
                        descriptor = descriptor + "!;";
                        previousWasNonNull = true;
                    } else {
                        return "";
                    }
                }
            }
            position++;
        }
        if (!named && name.length() != 0) {
            descriptor = descriptor + "N:" + name + ";";
            named = true;
        }
        return named && listDepth == 0 ? descriptor : "";
    }

    /** Returns the named leaf type, or an empty value for an invalid descriptor. */
    public static String namedType(String descriptor) {
        if (descriptor == null || descriptor.indexOf(PREFIX) != 0) {
            return "";
        }
        int start = descriptor.indexOf("N:");
        int end = start < 0 ? -1 : descriptor.indexOf(';', start + 2);
        return end <= start + 2 ? "" : descriptor.substring(start + 2, end);
    }

    /** Renders a validated postfix carrier back to canonical GraphQL type-reference text. */
    public static String sourceText(String descriptor) {
        String name = namedType(descriptor);
        if (name.length() == 0) {
            return "";
        }
        int nameStart = descriptor.indexOf("N:");
        int position = descriptor.indexOf(';', nameStart + 2) + 1;
        String rendered = name;
        int wrappers = 0;
        while (position < descriptor.length()) {
            int end = descriptor.indexOf(';', position);
            if (end <= position || wrappers > MAX_LIST_DEPTH * 2 + 2) {
                return "";
            }
            String wrapper = descriptor.substring(position, end);
            if (wrapper.equals("!")) {
                rendered = rendered + "!";
            } else if (wrapper.equals("L")) {
                rendered = "[" + rendered + "]";
            } else {
                return "";
            }
            wrappers++;
            position = end + 1;
        }
        return rendered;
    }

    /** Returns whether the typed reference contains one or more list wrappers. */
    public static boolean hasList(String descriptor) {
        return descriptor != null && descriptor.indexOf("L;") >= 0;
    }

    /** Returns whether the outermost type wrapper is non-null. */
    public static boolean isOuterNonNull(String descriptor) {
        return descriptor != null && descriptor.endsWith("!;");
    }

    /**
     * Returns whether the outermost named/wrapper value is a list, ignoring its optional outer
     * non-null marker.  Keeping this navigation on the scalar carrier lets the transpiled input
     * coercer apply GraphQL's singleton-to-list rule without reparsing source type text.
     */
    public static boolean isOuterList(String descriptor) {
        String withoutNonNull = withoutOuterNonNull(descriptor);
        return withoutNonNull.length() > PREFIX.length() && withoutNonNull.endsWith("L;");
    }

    /**
     * Returns the descriptor for the item of an outer list, including the item's own non-null
     * marker, or an empty value when {@code descriptor} is not a valid outer list.
     */
    public static String listItemType(String descriptor) {
        String withoutNonNull = withoutOuterNonNull(descriptor);
        if (!withoutNonNull.endsWith("L;")) {
            return "";
        }
        String item = withoutNonNull.substring(0, withoutNonNull.length() - 2);
        return namedType(item).length() == 0 ? "" : item;
    }

    /** Removes exactly the optional outer non-null marker from a valid descriptor. */
    public static String withoutOuterNonNull(String descriptor) {
        if (namedType(descriptor).length() == 0) {
            return "";
        }
        return isOuterNonNull(descriptor) ? descriptor.substring(0, descriptor.length() - 2) : descriptor;
    }

    /** Adds the canonical outer non-null wrapper, or returns empty for an invalid carrier. */
    public static String withOuterNonNull(String descriptor) {
        if (namedType(descriptor).length() == 0) {
            return "";
        }
        return isOuterNonNull(descriptor) ? descriptor : descriptor + "!;";
    }

    /**
     * Returns whether a variable declaration may be used at one input location.
     *
     * <p>This is GraphQL's wrapper-compatibility rule expressed over the scalar postfix carrier:
     * a non-null variable may flow to a nullable location, list item wrappers are compared
     * structurally, and a nullable declaration may supply a non-null location only when either
     * the variable has a non-null default or the location itself has a default. The two default
     * flags apply only at the outer location; nested list elements do not have defaults.</p>
     */
    public static boolean isVariableUsageAllowed(
            String variableType,
            String locationType,
            boolean variableHasNonNullDefault,
            boolean locationHasDefault
    ) {
        if (namedType(variableType).length() == 0 || namedType(locationType).length() == 0) {
            return false;
        }
        String variable = variableType;
        String location = locationType;
        boolean variableDefault = variableHasNonNullDefault;
        boolean locationDefault = locationHasDefault;
        int wrappers = 0;
        while (wrappers <= MAX_LIST_DEPTH * 2 + 2) {
            if (isOuterNonNull(location)) {
                location = withoutOuterNonNull(location);
                if (isOuterNonNull(variable)) {
                    variable = withoutOuterNonNull(variable);
                } else if (!variableDefault && !locationDefault) {
                    return false;
                }
                // Defaults affect only the original field/argument location, never an item
                // nested inside a list wrapper.
                variableDefault = false;
                locationDefault = false;
                wrappers++;
                continue;
            }
            if (isOuterNonNull(variable)) {
                variable = withoutOuterNonNull(variable);
                variableDefault = false;
                locationDefault = false;
                wrappers++;
                continue;
            }
            if (isOuterList(location)) {
                if (!isOuterList(variable)) {
                    return false;
                }
                variable = listItemType(variable);
                location = listItemType(location);
                variableDefault = false;
                locationDefault = false;
                if (variable.length() == 0 || location.length() == 0) {
                    return false;
                }
                wrappers++;
                continue;
            }
            return !isOuterList(variable) && namedType(variable).equals(namedType(location));
        }
        // Parsed references have at most MAX_LIST_DEPTH list wrappers. Refuse malformed direct
        // carriers rather than allowing an unbounded compatibility walk in a transpiled routine.
        return false;
    }
}
