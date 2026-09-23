package io.titan.graphql.database;

/**
 * Bounded, scalar-backed GraphQL language primitives shared by every generated database
 * package.
 *
 * <p>Titan's portable source subset deliberately does not depend on an in-memory parser object
 * graph. This class therefore represents tokens and document ranges as scalar strings, but it is
 * still a real lexical boundary: later language phases consume its token stream rather than
 * repeating ad-hoc character scans. Nothing here is HTTP-, model-, or JVM-runtime-specific.</p>
 */
public final class DatabaseGraphqlLanguage {

    private static final int MAX_LEXICAL_TOKENS = 8192;
    private static final int MAX_GRAPHQL_DELIMITER_NESTING = 128;
    private static final int MAX_QUERY_CHARACTERS = 16384;
    private static final int MAX_FRAGMENT_EXPANSIONS = 512;
    private static final int MAX_FRAGMENT_WORK_CHARACTERS = 262144;
    private static final String TOKEN_STREAM_PREFIX = "v1;";
    private static final char SYNTAX_INDEX_SEPARATOR = '|';
    private static final int NO_SYNTAX_INDEX_VALUE = -2;

    private DatabaseGraphqlLanguage() {
    }

    /**
     * Produces the bounded, invocation-local lexical representation {@code Kstart:end;}.
     * Token ranges use UTF-16 source offsets, are not persisted, and let parsers retain GraphQL
     * source locations without a mutable AST object graph.
     */
    public static String lexicalTokenStream(String source) {
        if (source == null) {
            return "";
        }
        int limit = source.length();
        int position = 0;
        int tokens = 0;
        String stream = TOKEN_STREAM_PREFIX;
        String expectedClosers = "";
        while (position < limit) {
            char current = source.charAt(position);
            if (isIgnored(current) || current == ',') {
                position++;
                continue;
            }
            if (current == '#') {
                position++;
                while (position < limit && source.charAt(position) != '\n' && source.charAt(position) != '\r') {
                    position++;
                }
                continue;
            }
            int start = position;
            String kind = "";
            if (isNameStart(current)) {
                kind = "N";
                position = nameEnd(source, position, limit);
            } else if (current == '-' || current >= '0' && current <= '9') {
                kind = "I";
                position = graphqlNumberEnd(source, position, limit);
                if (position < 0 || position > start
                        && (source.charAt(position - 1) == 'e' || source.charAt(position - 1) == 'E')) {
                    return "";
                }
                if (graphqlNumberIsFloat(source, start, position)) {
                    kind = "F";
                }
            } else if (current == '"') {
                kind = start + 2 < limit && source.charAt(start + 1) == '"'
                        && source.charAt(start + 2) == '"' ? "B" : "S";
                position = graphqlStringEnd(source, position, limit);
                if (position < 0) {
                    return "";
                }
            } else if (current == '.') {
                if (position + 2 >= limit || source.charAt(position + 1) != '.'
                        || source.charAt(position + 2) != '.') {
                    return "";
                }
                // Keep every descriptor a single leading kind character. This makes token range
                // decoding O(1) and avoids a second, nested scan over the token carrier.
                kind = "D";
                position += 3;
            } else if (graphqlPunctuation(current)) {
                kind = "P";
                if (current == '(' || current == '[' || current == '{') {
                    expectedClosers = expectedClosers + graphqlClosingDelimiter(current);
                    if (expectedClosers.length() > MAX_GRAPHQL_DELIMITER_NESTING) {
                        return "";
                    }
                } else if (current == ')' || current == ']' || current == '}') {
                    int closingDepth = expectedClosers.length() - 1;
                    if (closingDepth < 0 || expectedClosers.charAt(closingDepth) != current) {
                        return "";
                    }
                    expectedClosers = expectedClosers.substring(0, closingDepth);
                }
                position++;
            } else {
                return "";
            }
            tokens++;
            if (tokens > MAX_LEXICAL_TOKENS) {
                return "";
            }
            stream = stream + kind + start + ":" + position + ";";
        }
        return expectedClosers.length() == 0 ? stream : "";
    }

    /**
     * Returns the source offset that made lexical analysis fail, or {@code -1} when the source is
     * lexically valid or no meaningful source location exists. The normal success path never
     * calls this method: the public engine invokes it only after {@link #lexicalTokenStream}
     * rejects a bounded document, preserving the one-pass path for valid requests while giving
     * parser errors a stable GraphQL location.
     */
    public static int lexicalErrorOffset(String source) {
        if (source == null || source.length() == 0 || source.length() > MAX_QUERY_CHARACTERS) {
            return -1;
        }
        int limit = source.length();
        int position = 0;
        int tokens = 0;
        String expectedClosers = "";
        String openerOffsets = "";
        while (position < limit) {
            char current = source.charAt(position);
            if (isIgnored(current) || current == ',') {
                position++;
                continue;
            }
            if (current == '#') {
                position++;
                while (position < limit && source.charAt(position) != '\n' && source.charAt(position) != '\r') {
                    position++;
                }
                continue;
            }
            int start = position;
            if (isNameStart(current)) {
                position = nameEnd(source, position, limit);
            } else if (current == '-' || current >= '0' && current <= '9') {
                position = graphqlNumberEnd(source, position, limit);
                if (position < 0) {
                    return start;
                }
                if (position > start && (source.charAt(position - 1) == 'e' || source.charAt(position - 1) == 'E')) {
                    return position - 1;
                }
            } else if (current == '"') {
                position = graphqlStringEnd(source, position, limit);
                if (position < 0) {
                    return start;
                }
            } else if (current == '.') {
                if (position + 2 >= limit || source.charAt(position + 1) != '.'
                        || source.charAt(position + 2) != '.') {
                    return start;
                }
                position += 3;
            } else if (graphqlPunctuation(current)) {
                if (current == '(' || current == '[' || current == '{') {
                    expectedClosers = expectedClosers + graphqlClosingDelimiter(current);
                    openerOffsets = openerOffsets + start + ";";
                    if (expectedClosers.length() > MAX_GRAPHQL_DELIMITER_NESTING) {
                        return start;
                    }
                } else if (current == ')' || current == ']' || current == '}') {
                    int closingDepth = expectedClosers.length() - 1;
                    if (closingDepth < 0 || expectedClosers.charAt(closingDepth) != current) {
                        return start;
                    }
                    expectedClosers = expectedClosers.substring(0, closingDepth);
                    int lastSeparator = previousOpenerSeparator(openerOffsets);
                    openerOffsets = lastSeparator < 0 ? "" : openerOffsets.substring(0, lastSeparator + 1);
                }
                position++;
            } else {
                return start;
            }
            tokens++;
            if (tokens > MAX_LEXICAL_TOKENS) {
                return start;
            }
        }
        if (expectedClosers.length() == 0) {
            return -1;
        }
        int lastSeparator = previousOpenerSeparator(openerOffsets);
        int offsetStart = lastSeparator < 0 ? 0 : lastSeparator + 1;
        int offsetEnd = openerOffsets.length() - 1;
        return decimal(openerOffsets, offsetStart, offsetEnd);
    }

    /** Finds the separator before the required trailing separator in the scalar offset stack. */
    private static int previousOpenerSeparator(String openerOffsets) {
        int position = 0;
        int previous = -1;
        while (position + 1 < openerOffsets.length()) {
            if (openerOffsets.charAt(position) == ';') {
                previous = position;
            }
            position++;
        }
        return previous;
    }

    /**
     * Creates the request-local scalar language plan consumed by generated execution code.
     *
     * <p>The value is an invocation-local token-and-selection carrier, never client input or
     * persisted state. It contains the bounded lexical stream followed by a scalar syntax index
     * for operations, variable definitions, and fields. It is created once after
     * selected-operation reduction and then passed to execution helpers so they do not
     * independently lex or rediscover operation-header and field boundaries in the same
     * GraphQL source.</p>
     */
    public static String documentPlan(String source) {
        String tokens = lexicalTokenStream(source);
        if (tokens.length() == 0) {
            return "";
        }
        String syntaxIndex = selectionSyntaxIndex(source, tokens);
        return syntaxIndex.length() == 0 ? "" : tokens + SYNTAX_INDEX_SEPARATOR + syntaxIndex;
    }

    /**
     * Returns {@code start:end} for exactly one operation selected from a complete document.
     * The end is exclusive. Both offsets are emitted by consuming {@link #lexicalTokenStream}
     * rather than by a second character-level document scanner.
     */
    public static String selectedOperationRange(String source, String operationName) {
        String tokens = lexicalTokenStream(source);
        if (tokens.length() == 0) {
            return "";
        }
        return selectedOperationRangeFromTokens(source, tokens, operationName);
    }

    /**
     * Selects an operation from a caller-retained lexical token plan. This is the document-core
     * counterpart of {@link #selectedOperationRange(String, String)}: it deliberately consumes
     * the bounded token carrier rather than lexing the complete request a second time.
     */
    public static String selectedOperationRange(String source, String tokens, String operationName) {
        if (!tokenPlanIsUsable(tokens)) {
            return "";
        }
        return selectedOperationRangeFromTokens(source, tokens, operationName);
    }

    /** Returns the selected operation's GraphQL kind, or an empty value when selection fails. */
    public static String selectedOperationKind(String source, String operationName) {
        String tokens = lexicalTokenStream(source);
        if (tokens.length() == 0) {
            return "";
        }
        String range = selectedOperationRangeFromTokens(source, tokens, operationName);
        int selectedStart = rangeStart(range);
        if (selectedStart < 0) {
            return "";
        }
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor < tokenLimit(tokens)) {
            int start = tokenStart(tokens, cursor);
            if (start < 0) {
                return "";
            }
            if (start == selectedStart) {
                return tokenIsPunctuation(source, tokens, cursor, '{') ? "query" : tokenText(source, tokens, cursor);
            }
            if (start > selectedStart) {
                return "";
            }
            cursor = nextToken(tokens, cursor);
        }
        return "";
    }

    /** Returns the selected operation kind retained in an invocation-local language plan. */
    public static String operationKind(String tokens) {
        int position = syntaxIndexStart(tokens);
        if (position < 0 || position + 3 >= tokens.length() || tokens.charAt(position + 1) != 'O') {
            return "";
        }
        int firstColon = tokens.indexOf(':', position + 2);
        int secondColon = firstColon < 0 ? -1 : tokens.indexOf(':', firstColon + 1);
        int end = secondColon < 0 ? -1 : tokens.indexOf(';', secondColon + 1);
        if (secondColon < 0 || end != secondColon + 2) {
            return "";
        }
        char kind = tokens.charAt(secondColon + 1);
        if (kind == 'q') {
            return "query";
        }
        if (kind == 'm') {
            return "mutation";
        }
        return kind == 's' ? "subscription" : "";
    }

    /** Returns the first operation's root selection-set offset from the tokenized document. */
    public static int firstOperationSelectionStart(String source) {
        return firstOperationSelectionStart(source, documentPlan(source));
    }

    /** Consumes a trusted invocation-local {@link #documentPlan(String)} for the root selection. */
    public static int firstOperationSelectionStart(String source, String tokens) {
        if (!tokenPlanIsUsable(tokens)) {
            return -1;
        }
        int indexedSelection = syntaxIndexOperationSelection(tokens);
        if (indexedSelection != NO_SYNTAX_INDEX_VALUE) {
            return indexedSelection;
        }
        int cursor = TOKEN_STREAM_PREFIX.length();
        if (tokenIsPunctuation(source, tokens, cursor, '{')) {
            return tokenStart(tokens, cursor);
        }
        if (!tokenIsName(source, tokens, cursor, "query")
                && !tokenIsName(source, tokens, cursor, "mutation")
                && !tokenIsName(source, tokens, cursor, "subscription")) {
            return -1;
        }
        cursor = nextToken(tokens, cursor);
        if (tokenIsName(source, tokens, cursor, "")) {
            cursor = nextToken(tokens, cursor);
        }
        int selection = operationSelectionToken(source, tokens, cursor);
        return selection < 0 ? -1 : tokenStart(tokens, selection);
    }

    /**
     * Returns the source offset immediately after a field's name/alias and optional arguments.
     * Directives remain intentionally visible to the caller because directive evaluation belongs
     * to the later typed execution-plan phase.
     */
    public static int fieldHeaderEnd(String source, int fieldStart) {
        return fieldHeaderEnd(source, documentPlan(source), fieldStart);
    }

    /** Consumes a trusted invocation-local token plan for one field header. */
    public static int fieldHeaderEnd(String source, String tokens, int fieldStart) {
        if (!tokenPlanIsUsable(tokens)) {
            return -1;
        }
        int indexedHeaderEnd = syntaxIndexFieldValue(tokens, fieldStart, 1);
        if (indexedHeaderEnd != NO_SYNTAX_INDEX_VALUE) {
            return indexedHeaderEnd;
        }
        int cursor = tokenAtSourceOffset(tokens, fieldStart);
        if (!tokenIsName(source, tokens, cursor, "")) {
            return -1;
        }
        cursor = nextToken(tokens, cursor);
        if (tokenIsPunctuation(source, tokens, cursor, ':')) {
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
        }
        if (tokenIsPunctuation(source, tokens, cursor, '(')) {
            int argumentsEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
            if (argumentsEnd < 0) {
                return -1;
            }
            cursor = nextToken(tokens, argumentsEnd);
        }
        return cursor < TOKEN_STREAM_PREFIX.length() || cursor >= tokenLimit(tokens) ? -1 : tokenStart(tokens, cursor);
    }

    /** Returns a field's direct nested selection-set offset, or {@code -1} for a leaf field. */
    public static int fieldSelectionStart(String source, int fieldStart) {
        return fieldSelectionStart(source, documentPlan(source), fieldStart);
    }

    /** Consumes a trusted invocation-local token plan for one field's nested selection. */
    public static int fieldSelectionStart(String source, String tokens, int fieldStart) {
        if (!tokenPlanIsUsable(tokens)) {
            return -1;
        }
        int indexedSelection = syntaxIndexFieldValue(tokens, fieldStart, 2);
        if (indexedSelection != NO_SYNTAX_INDEX_VALUE) {
            return indexedSelection;
        }
        int cursor = tokenAtSourceOffset(tokens, fieldStart);
        if (!tokenIsName(source, tokens, cursor, "")) {
            return -1;
        }
        cursor = nextToken(tokens, cursor);
        if (tokenIsPunctuation(source, tokens, cursor, ':')) {
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
        }
        if (tokenIsPunctuation(source, tokens, cursor, '(')) {
            int argumentsEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
            if (argumentsEnd < 0) {
                return -1;
            }
            cursor = nextToken(tokens, argumentsEnd);
        }
        while (tokenIsPunctuation(source, tokens, cursor, '@')) {
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
            if (tokenIsPunctuation(source, tokens, cursor, '(')) {
                int directiveEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
                if (directiveEnd < 0) {
                    return -1;
                }
                cursor = nextToken(tokens, directiveEnd);
            }
        }
        return tokenIsPunctuation(source, tokens, cursor, '{') ? tokenStart(tokens, cursor) : -1;
    }

    /**
     * Returns the source range for one parsed field-argument value as {@code start:end}.
     * Argument names and complete value boundaries are retained while the selection syntax is
     * built, so generated bindings do not need to re-walk a field header to resolve a literal or
     * variable reference. An empty result means the argument is absent or the plan is invalid.
     */
    public static String fieldArgumentValueRange(
            String source,
            String tokens,
            int fieldStart,
            String expectedArgument
    ) {
        if (!tokenPlanIsUsable(tokens) || source == null || expectedArgument == null
                || expectedArgument.length() == 0 || fieldStart < 0) {
            return "";
        }
        int indexStart = syntaxIndexStart(tokens);
        if (indexStart < 0) {
            return "";
        }
        // The scalar index is bounded by the lexical token budget. A linear literal-cursor scan
        // is deliberately retained for portable Titan lowering; `A` cannot occur inside its
        // numeric-only record payload.
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'A') {
                int first = tokens.indexOf(':', position + 1);
                int second = first < 0 ? -1 : tokens.indexOf(':', first + 1);
                int third = second < 0 ? -1 : tokens.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : tokens.indexOf(':', third + 1);
                int end = fourth < 0 ? -1 : tokens.indexOf(';', fourth + 1);
                if (first < 0 || second < 0 || third < 0 || fourth < 0 || end < 0) {
                    return "";
                }
                int candidateFieldStart = decimal(tokens, position + 1, first);
                int nameStart = decimal(tokens, first + 1, second);
                int nameEnd = decimal(tokens, second + 1, third);
                int valueStart = decimal(tokens, third + 1, fourth);
                int valueEnd = decimal(tokens, fourth + 1, end);
                if (candidateFieldStart == fieldStart && nameStart >= 0 && nameEnd > nameStart
                        && valueStart >= 0 && valueEnd > valueStart && valueEnd <= source.length()
                        && source.substring(nameStart, nameEnd).equals(expectedArgument)) {
                    return valueStart + ":" + valueEnd;
                }
            }
            position++;
        }
        return "";
    }

    /** Returns a field's response key retained in the syntax index, including an alias. */
    public static String fieldResponseKey(String source, String tokens, int fieldStart) {
        return fieldIdentityValue(source, tokens, fieldStart, 1);
    }

    /** Returns a field's actual schema name retained in the syntax index, excluding an alias. */
    public static String fieldName(String source, String tokens, int fieldStart) {
        return fieldIdentityValue(source, tokens, fieldStart, 2);
    }

    /** Returns the source range of a field's actual schema name, excluding an alias. */
    public static String fieldNameRange(String source, String tokens, int fieldStart) {
        return fieldIdentityRange(source, tokens, fieldStart, 2);
    }

    /**
     * Compares two complete lexical-token ranges. Whitespace and comments never enter the token
     * stream, while source text is compared only for the bounded payload of already-lexed tokens.
     */
    public static boolean tokenRangesEqual(
            String source, String tokens, int firstStart, int firstEnd, int secondStart, int secondEnd
    ) {
        if (!tokenPlanIsUsable(tokens) || source == null || firstStart < 0 || firstEnd <= firstStart
                || secondStart < 0 || secondEnd <= secondStart || firstEnd > source.length()
                || secondEnd > source.length()) {
            return false;
        }
        int first = tokenAtSourceOffset(tokens, firstStart);
        int second = tokenAtSourceOffset(tokens, secondStart);
        int limit = tokenLimit(tokens);
        while (true) {
            if (first < TOKEN_STREAM_PREFIX.length() || second < TOKEN_STREAM_PREFIX.length()
                    || first > limit || second > limit) {
                return false;
            }
            if (first == limit || second == limit) {
                return first == limit && second == limit;
            }
            int firstTokenStart = tokenStart(tokens, first);
            int firstTokenEnd = tokenEnd(tokens, first);
            int secondTokenStart = tokenStart(tokens, second);
            int secondTokenEnd = tokenEnd(tokens, second);
            boolean firstComplete = firstTokenStart == firstEnd;
            boolean secondComplete = secondTokenStart == secondEnd;
            if (firstComplete || secondComplete) {
                return firstComplete && secondComplete;
            }
            if (firstTokenStart < firstStart || firstTokenEnd <= firstTokenStart || firstTokenEnd > firstEnd
                    || secondTokenStart < secondStart || secondTokenEnd <= secondTokenStart
                    || secondTokenEnd > secondEnd || !tokenKind(tokens, first).equals(tokenKind(tokens, second))
                    || firstTokenEnd - firstTokenStart != secondTokenEnd - secondTokenStart) {
                return false;
            }
            int offset = 0;
            while (offset < firstTokenEnd - firstTokenStart) {
                if (source.charAt(firstTokenStart + offset) != source.charAt(secondTokenStart + offset)) {
                    return false;
                }
                offset++;
            }
            first = nextToken(tokens, first);
            second = nextToken(tokens, second);
        }
    }

    /**
     * Concatenates the already-lexed tokens in one bounded source range. This normalizes GraphQL
     * ignored text and commas without a second source-character scan, which is useful for type
     * descriptors whose semantic spelling must be independent of formatting.
     */
    public static String tokenRangeText(String source, String tokens, int rangeStart, int rangeEnd) {
        if (!tokenPlanIsUsable(tokens) || source == null || rangeStart < 0 || rangeEnd <= rangeStart
                || rangeEnd > source.length()) {
            return "";
        }
        int cursor = tokenAtSourceOffset(tokens, rangeStart);
        int limit = tokenLimit(tokens);
        String normalized = "";
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < limit) {
            int start = tokenStart(tokens, cursor);
            int end = tokenEnd(tokens, cursor);
            if (start == rangeEnd) {
                return normalized;
            }
            if (start < rangeStart || end <= start || end > rangeEnd) {
                return "";
            }
            normalized = normalized + source.substring(start, end);
            if (end == rangeEnd) {
                return normalized;
            }
            cursor = nextToken(tokens, cursor);
        }
        return normalized.length() == 0 ? "" : normalized;
    }

    /**
     * Returns a named fragment's parsed header as
     * {@code typeStart:typeEnd:directivesStart:selectionStart:selectionEnd}, or an empty string
     * when it is absent, malformed, or defined more than once.
     */
    public static String fragmentDefinitionInfo(String source, String tokens, String expectedName) {
        if (!tokenPlanIsUsable(tokens) || source == null || expectedName == null || expectedName.length() == 0) {
            return "";
        }
        int indexStart = syntaxIndexStart(tokens);
        String found = "";
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'R') {
                int first = tokens.indexOf(':', position + 1);
                int second = first < 0 ? -1 : tokens.indexOf(':', first + 1);
                int third = second < 0 ? -1 : tokens.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : tokens.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : tokens.indexOf(':', fourth + 1);
                int sixth = fifth < 0 ? -1 : tokens.indexOf(':', fifth + 1);
                int end = sixth < 0 ? -1 : tokens.indexOf(';', sixth + 1);
                if (first < 0 || second < 0 || third < 0 || fourth < 0 || fifth < 0 || sixth < 0 || end < 0) {
                    return "";
                }
                int nameStart = decimal(tokens, position + 1, first);
                int nameEnd = decimal(tokens, first + 1, second);
                int typeStart = decimal(tokens, second + 1, third);
                int typeEnd = decimal(tokens, third + 1, fourth);
                int directivesStart = decimal(tokens, fourth + 1, fifth);
                int selectionStart = decimal(tokens, fifth + 1, sixth);
                int selectionEnd = decimal(tokens, sixth + 1, end);
                if (nameStart < 0 || nameEnd <= nameStart || typeStart < 0 || typeEnd <= typeStart
                        || directivesStart < typeEnd || selectionStart < directivesStart
                        || selectionEnd <= selectionStart || selectionEnd > source.length()) {
                    return "";
                }
                if (source.substring(nameStart, nameEnd).equals(expectedName)) {
                    if (found.length() != 0) {
                        return "";
                    }
                    found = typeStart + ":" + typeEnd + ":" + directivesStart + ":" + selectionStart
                            + ":" + selectionEnd;
                }
            }
            position++;
        }
        return found;
    }

    /** Returns one zero-based value from {@link #fragmentDefinitionInfo(String, String, String)}. */
    public static int fragmentDefinitionInfoValue(String info, int index) {
        return planInfoValue(info, index);
    }

    /** Returns one zero-based decimal or {@code n} value from a scalar syntax-info record. */
    public static int planInfoValue(String info, int index) {
        // AST field metadata has six slots (header, child selection, response-key range, and
        // schema-name range). Keep this generic bounded scalar reader large enough for that
        // carrier as well as the five-slot fragment records it originally served.
        if (info == null || index < 0 || index > 5) {
            return -1;
        }
        int start = 0;
        int valueIndex = 0;
        while (valueIndex < index) {
            int separator = info.indexOf(':', start);
            if (separator < start) {
                return -1;
            }
            start = separator + 1;
            valueIndex++;
        }
        int end = info.indexOf(':', start);
        if (end < 0) {
            end = info.length();
        }
        return end <= start ? -1 : decimal(info, start, end);
    }

    /** Returns {@code nodeStart:kind} for one parsed direct selection item, or an empty string. */
    public static String selectionItemInfo(String tokens, int selectionStart, int ordinal) {
        if (!tokenPlanIsUsable(tokens) || selectionStart < 0 || ordinal < 0) {
            return "";
        }
        int indexStart = syntaxIndexStart(tokens);
        if (indexStart < 0) {
            return "";
        }
        int position = 0;
        int found = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'C') {
                int first = tokens.indexOf(':', position + 1);
                int end = first < 0 ? -1 : tokens.indexOf(';', first + 1);
                if (first < 0 || end < 0 || decimal(tokens, position + 1, first) != selectionStart) {
                    if (first < 0 || end < 0) {
                        return "";
                    }
                } else {
                    if (found == ordinal) {
                        return tokens.substring(first + 1, end);
                    }
                    found = found + 1;
                }
            }
            position++;
        }
        return "";
    }

    /** Counts parsed direct selection items for one selection-set opening offset. */
    public static int selectionItemCount(String tokens, int selectionStart) {
        int indexStart = syntaxIndexStart(tokens);
        if (!tokenPlanIsUsable(tokens) || selectionStart < 0 || indexStart < 0) {
            return -1;
        }
        int position = 0;
        int count = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'C') {
                int first = tokens.indexOf(':', position + 1);
                int end = first < 0 ? -1 : tokens.indexOf(';', first + 1);
                if (first < 0 || end < 0) {
                    return -1;
                }
                if (decimal(tokens, position + 1, first) == selectionStart) {
                    count++;
                }
            }
            position++;
        }
        return count;
    }

    /** Returns a named-spread record as {@code nameStart:nameEnd:directivesStart}. */
    public static String namedFragmentSpreadInfo(String tokens, int spreadStart) {
        return syntaxIndexNodeInfo(tokens, 'P', spreadStart);
    }

    /** Returns an inline-spread record as {@code typeStart:typeEnd:directivesStart:selectionStart:selectionEnd}. */
    public static String inlineFragmentInfo(String tokens, int spreadStart) {
        return syntaxIndexNodeInfo(tokens, 'I', spreadStart);
    }

    private static String fieldIdentityValue(String source, String tokens, int fieldStart, int valueIndex) {
        String range = fieldIdentityRange(source, tokens, fieldStart, valueIndex);
        int start = rangeStart(range);
        int end = rangeEnd(range);
        return start < 0 || end <= start || end > source.length() ? "" : source.substring(start, end);
    }

    private static String fieldIdentityRange(String source, String tokens, int fieldStart, int valueIndex) {
        if (!tokenPlanIsUsable(tokens) || source == null || fieldStart < 0) {
            return "";
        }
        int indexStart = syntaxIndexStart(tokens);
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'N') {
                int first = tokens.indexOf(':', position + 1);
                int second = first < 0 ? -1 : tokens.indexOf(':', first + 1);
                int third = second < 0 ? -1 : tokens.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : tokens.indexOf(':', third + 1);
                int end = fourth < 0 ? -1 : tokens.indexOf(';', fourth + 1);
                if (first < 0 || second < 0 || third < 0 || fourth < 0 || end < 0) {
                    return "";
                }
                if (decimal(tokens, position + 1, first) == fieldStart) {
                    int start = valueIndex == 1 ? decimal(tokens, first + 1, second)
                            : decimal(tokens, third + 1, fourth);
                    int valueEnd = valueIndex == 1 ? decimal(tokens, second + 1, third)
                            : decimal(tokens, fourth + 1, end);
                    return start < 0 || valueEnd <= start || valueEnd > source.length()
                            ? "" : start + ":" + valueEnd;
                }
            }
            position++;
        }
        return "";
    }

    private static String syntaxIndexNodeInfo(String tokens, char kind, int nodeStart) {
        if (!tokenPlanIsUsable(tokens) || nodeStart < 0) {
            return "";
        }
        int indexStart = syntaxIndexStart(tokens);
        if (indexStart < 0) {
            return "";
        }
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == kind) {
                int first = tokens.indexOf(':', position + 1);
                int end = first < 0 ? -1 : tokens.indexOf(';', first + 1);
                if (first < 0 || end < 0) {
                    return "";
                }
                if (decimal(tokens, position + 1, first) == nodeStart) {
                    return tokens.substring(first + 1, end);
                }
            }
            position++;
        }
        return "";
    }

    /** Counts parsed arguments for one field, or {@code -1} when the field node is absent. */
    public static int fieldArgumentCount(String tokens, int fieldStart) {
        if (!tokenPlanIsUsable(tokens) || syntaxIndexFieldValue(tokens, fieldStart, 1) < 0) {
            return -1;
        }
        return nodeArgumentCount(tokens, fieldStart);
    }

    /** Counts argument nodes for a parsed field, directive, or future AST node owner. */
    public static int nodeArgumentCount(String tokens, int ownerStart) {
        if (!tokenPlanIsUsable(tokens) || ownerStart < 0) {
            return -1;
        }
        int indexStart = syntaxIndexStart(tokens);
        int count = 0;
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'A') {
                int first = tokens.indexOf(':', position + 1);
                int second = first < 0 ? -1 : tokens.indexOf(':', first + 1);
                int third = second < 0 ? -1 : tokens.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : tokens.indexOf(':', third + 1);
                int end = fourth < 0 ? -1 : tokens.indexOf(';', fourth + 1);
                if (first < 0 || second < 0 || third < 0 || fourth < 0 || end < 0) {
                    return -1;
                }
                if (decimal(tokens, position + 1, first) == ownerStart) {
                    count++;
                    if (count > MAX_LEXICAL_TOKENS) {
                        return -1;
                    }
                }
            }
            position++;
        }
        return count;
    }

    /**
     * Validates parsed field-argument names against an exact comma-delimited descriptor and
     * rejects duplicates without re-walking source text.
     */
    public static boolean fieldHasOnlyArguments(
            String source,
            String tokens,
            int fieldStart,
            String allowedNames
    ) {
        if (!tokenPlanIsUsable(tokens) || source == null || syntaxIndexFieldValue(tokens, fieldStart, 1) < 0) {
            return false;
        }
        return nodeHasOnlyArguments(source, tokens, fieldStart, allowedNames);
    }

    /** Validates argument names for a parsed field, directive, or future AST node owner. */
    public static boolean nodeHasOnlyArguments(
            String source,
            String tokens,
            int ownerStart,
            String allowedNames
    ) {
        if (!tokenPlanIsUsable(tokens) || source == null || ownerStart < 0) {
            return false;
        }
        int indexStart = syntaxIndexStart(tokens);
        String seen = ",";
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'A') {
                int first = tokens.indexOf(':', position + 1);
                int second = first < 0 ? -1 : tokens.indexOf(':', first + 1);
                int third = second < 0 ? -1 : tokens.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : tokens.indexOf(':', third + 1);
                int end = fourth < 0 ? -1 : tokens.indexOf(';', fourth + 1);
                if (first < 0 || second < 0 || third < 0 || fourth < 0 || end < 0) {
                    return false;
                }
                if (decimal(tokens, position + 1, first) == ownerStart) {
                    int nameStart = decimal(tokens, first + 1, second);
                    int nameEnd = decimal(tokens, second + 1, third);
                    if (nameStart < 0 || nameEnd <= nameStart || nameEnd > source.length()) {
                        return false;
                    }
                    String name = source.substring(nameStart, nameEnd);
                    if (!commaSeparatedNameContains(allowedNames, name) || commaSeparatedNameContains(seen, name)) {
                        return false;
                    }
                    seen = seen + name + ",";
                }
            }
            position++;
        }
        return true;
    }

    /** Returns a parsed directive's name, or an empty value when its plan node is absent. */
    public static String directiveName(String source, String tokens, int directiveStart) {
        if (!tokenPlanIsUsable(tokens) || source == null || directiveStart < 0) {
            return "";
        }
        int indexStart = syntaxIndexStart(tokens);
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'D') {
                int first = tokens.indexOf(':', position + 1);
                int second = first < 0 ? -1 : tokens.indexOf(':', first + 1);
                int third = second < 0 ? -1 : tokens.indexOf(':', second + 1);
                int end = third < 0 ? -1 : tokens.indexOf(';', third + 1);
                if (first < 0 || second < 0 || third < 0 || end < 0) {
                    return "";
                }
                if (decimal(tokens, position + 1, first) == directiveStart) {
                    int nameStart = decimal(tokens, second + 1, third);
                    int nameEnd = decimal(tokens, third + 1, end);
                    return nameStart < 0 || nameEnd <= nameStart || nameEnd > source.length()
                            ? "" : source.substring(nameStart, nameEnd);
                }
            }
            position++;
        }
        return "";
    }

    /** Returns a parsed directive's exclusive source end, or {@code -1} when absent. */
    public static int directiveEnd(String tokens, int directiveStart) {
        if (!tokenPlanIsUsable(tokens) || directiveStart < 0) {
            return -1;
        }
        int indexStart = syntaxIndexStart(tokens);
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'D') {
                int first = tokens.indexOf(':', position + 1);
                int second = first < 0 ? -1 : tokens.indexOf(':', first + 1);
                int third = second < 0 ? -1 : tokens.indexOf(':', second + 1);
                int end = third < 0 ? -1 : tokens.indexOf(';', third + 1);
                if (first < 0 || second < 0 || third < 0 || end < 0) {
                    return -1;
                }
                if (decimal(tokens, position + 1, first) == directiveStart) {
                    return decimal(tokens, first + 1, second);
                }
            }
            position++;
        }
        return -1;
    }

    /** Returns the first retained token start at or after a source offset, or {@code -1}. */
    public static int nextTokenStartAtOrAfter(String tokens, int sourceOffset) {
        if (!tokenPlanIsUsable(tokens) || sourceOffset < 0) {
            return -1;
        }
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor < tokenLimit(tokens)) {
            int start = tokenStart(tokens, cursor);
            if (start < 0) {
                return -1;
            }
            if (start >= sourceOffset) {
                return start;
            }
            cursor = nextToken(tokens, cursor);
        }
        return -1;
    }

    /** Whether a parsed lexical token at the supplied offset is the expected punctuation. */
    public static boolean tokenIsPunctuationAt(String source, String tokens, int sourceOffset, char expected) {
        return source != null && tokenIsPunctuation(source, tokens, tokenAtSourceOffset(tokens, sourceOffset), expected);
    }

    /**
     * Whether one bounded GraphQL value is exactly the {@code null} literal. The answer comes
     * from its retained lexical token, so string/block-string content and ignored source text
     * cannot be mistaken for a literal.
     */
    public static boolean valueIsNullLiteral(String source, String tokens, int valueStart, int valueEnd) {
        if (!tokenPlanIsUsable(tokens) || source == null || valueStart < 0 || valueEnd <= valueStart
                || valueEnd > source.length()) {
            return false;
        }
        int cursor = tokenAtSourceOffset(tokens, valueStart);
        return tokenIsName(source, tokens, cursor, "null") && tokenEnd(tokens, cursor) == valueEnd;
    }

    /**
     * Returns the first {@code $name} range within one bounded GraphQL value, or an empty range.
     * This is used for the grammar rule that operation-variable defaults must be constant values;
     * token traversal deliberately does not inspect string/block-string content as source text.
     */
    public static String valueVariableReferenceRange(
            String source, String tokens, int valueStart, int valueEnd
    ) {
        if (!tokenPlanIsUsable(tokens) || source == null || valueStart < 0 || valueEnd <= valueStart
                || valueEnd > source.length()) {
            return "";
        }
        int cursor = tokenAtSourceOffset(tokens, valueStart);
        int limit = tokenLimit(tokens);
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < limit) {
            int start = tokenStart(tokens, cursor);
            int end = tokenEnd(tokens, cursor);
            if (start < valueStart || end <= start || end > valueEnd) {
                return "";
            }
            if (tokenIsPunctuation(source, tokens, cursor, '$')) {
                int name = nextToken(tokens, cursor);
                if (!tokenIsName(source, tokens, name, "") || tokenEnd(tokens, name) > valueEnd) {
                    return "";
                }
                return start + ":" + tokenEnd(tokens, name);
            }
            if (end == valueEnd) {
                return "";
            }
            cursor = nextToken(tokens, cursor);
        }
        return "";
    }

    /** Validates the GraphQL-literal form of an input object against a comma-separated field set. */
    public static boolean graphqlInputObjectHasOnlyFields(String source, String allowedNames) {
        return graphqlInputObjectHasOnlyFields(source, lexicalTokenStream(source), allowedNames);
    }

    /** Consumes a caller-retained lexical carrier for GraphQL input-object validation. */
    public static boolean graphqlInputObjectHasOnlyFields(String source, String tokens, String allowedNames) {
        int object = TOKEN_STREAM_PREFIX.length();
        if (!tokenIsPunctuation(source, tokens, object, '{')) {
            return false;
        }
        int objectEnd = matchingDelimiter(source, tokens, object, '{', '}');
        if (objectEnd < 0 || nextToken(tokens, objectEnd) != tokenLimit(tokens)) {
            return false;
        }
        String seen = ",";
        int cursor = nextToken(tokens, object);
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < objectEnd) {
            if (!tokenIsName(source, tokens, cursor, "")) {
                return false;
            }
            String name = tokenText(source, tokens, cursor);
            if (!commaSeparatedNameContains(allowedNames, name) || commaSeparatedNameContains(seen, name)) {
                return false;
            }
            seen = seen + name + ",";
            cursor = nextToken(tokens, cursor);
            if (!tokenIsPunctuation(source, tokens, cursor, ':')) {
                return false;
            }
            cursor = nextToken(tokens, cursor);
            int valueEnd = valueEndToken(source, tokens, cursor, objectEnd);
            if (valueEnd < cursor || valueEnd >= objectEnd) {
                return false;
            }
            cursor = nextToken(tokens, valueEnd);
        }
        return cursor == objectEnd;
    }

    /** Returns one raw GraphQL input-object field value, or an empty value when absent/malformed. */
    public static String graphqlInputObjectFieldValue(String source, String expectedName) {
        return graphqlInputObjectFieldValue(source, lexicalTokenStream(source), expectedName);
    }

    /** Consumes a caller-retained lexical carrier for GraphQL input-object field lookup. */
    public static String graphqlInputObjectFieldValue(String source, String tokens, String expectedName) {
        if (expectedName == null || expectedName.length() == 0) {
            return "";
        }
        int object = TOKEN_STREAM_PREFIX.length();
        if (!tokenIsPunctuation(source, tokens, object, '{')) {
            return "";
        }
        int objectEnd = matchingDelimiter(source, tokens, object, '{', '}');
        if (objectEnd < 0 || nextToken(tokens, objectEnd) != tokenLimit(tokens)) {
            return "";
        }
        int cursor = nextToken(tokens, object);
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < objectEnd) {
            if (!tokenIsName(source, tokens, cursor, "")) {
                return "";
            }
            String name = tokenText(source, tokens, cursor);
            cursor = nextToken(tokens, cursor);
            if (!tokenIsPunctuation(source, tokens, cursor, ':')) {
                return "";
            }
            cursor = nextToken(tokens, cursor);
            int valueStart = tokenStart(tokens, cursor);
            int valueEnd = valueEndToken(source, tokens, cursor, objectEnd);
            if (valueStart < 0 || valueEnd < cursor || valueEnd >= objectEnd) {
                return "";
            }
            if (name.equals(expectedName)) {
                return source.substring(valueStart, tokenEnd(tokens, valueEnd));
            }
            cursor = nextToken(tokens, valueEnd);
        }
        return "";
    }

    /** Counts the values in one complete GraphQL/JSON-compatible list literal, or {@code -1}. */
    public static int graphqlInputListValueCount(String source) {
        return graphqlInputListValueCount(source, lexicalTokenStream(source));
    }

    /** Consumes a caller-retained lexical carrier for GraphQL/JSON-compatible list counting. */
    public static int graphqlInputListValueCount(String source, String tokens) {
        int list = TOKEN_STREAM_PREFIX.length();
        if (!tokenIsPunctuation(source, tokens, list, '[')) {
            return -1;
        }
        int listEnd = matchingDelimiter(source, tokens, list, '[', ']');
        if (listEnd < 0 || nextToken(tokens, listEnd) != tokenLimit(tokens)) {
            return -1;
        }
        int cursor = nextToken(tokens, list);
        int count = 0;
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < listEnd) {
            int valueEnd = valueEndToken(source, tokens, cursor, listEnd);
            if (valueEnd < cursor || valueEnd >= listEnd) {
                return -1;
            }
            count++;
            cursor = nextToken(tokens, valueEnd);
        }
        return cursor == listEnd ? count : -1;
    }

    /** Returns one raw value from a complete GraphQL/JSON-compatible list literal. */
    public static String graphqlInputListValue(String source, int ordinal) {
        return graphqlInputListValue(source, lexicalTokenStream(source), ordinal);
    }

    /** Consumes a caller-retained lexical carrier for GraphQL/JSON-compatible list lookup. */
    public static String graphqlInputListValue(String source, String tokens, int ordinal) {
        if (ordinal < 0) {
            return "";
        }
        int list = TOKEN_STREAM_PREFIX.length();
        if (!tokenIsPunctuation(source, tokens, list, '[')) {
            return "";
        }
        int listEnd = matchingDelimiter(source, tokens, list, '[', ']');
        if (listEnd < 0 || nextToken(tokens, listEnd) != tokenLimit(tokens)) {
            return "";
        }
        int cursor = nextToken(tokens, list);
        int index = 0;
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < listEnd) {
            int valueStart = tokenStart(tokens, cursor);
            int valueEnd = valueEndToken(source, tokens, cursor, listEnd);
            if (valueStart < 0 || valueEnd < cursor || valueEnd >= listEnd) {
                return "";
            }
            if (index == ordinal) {
                return source.substring(valueStart, tokenEnd(tokens, valueEnd));
            }
            index++;
            cursor = nextToken(tokens, valueEnd);
        }
        return "";
    }

    /**
     * Compares two complete GraphQL input values for field-merge identity. Lists retain their
     * order, scalar/enum/variable tokens retain their exact identity, and input-object fields are
     * compared as the unordered keyed values required by the GraphQL language. The work queue is
     * scalar encoded so nested values do not become recursive stored-routine calls after Titan
     * transpilation.
     */
    public static boolean inputValuesAreEquivalent(String firstValue, String secondValue) {
        if (firstValue == null || secondValue == null || firstValue.length() == 0
                || secondValue.length() == 0 || firstValue.length() > MAX_QUERY_CHARACTERS
                || secondValue.length() > MAX_QUERY_CHARACTERS) {
            return false;
        }
        String pending = inputValueComparisonWorkItem(firstValue, secondValue);
        int position = 0;
        int compared = 0;
        while (position < pending.length()) {
            int firstLengthEnd = pending.indexOf(':', position);
            int firstLength = decimal(pending, position, firstLengthEnd);
            int firstStart = firstLengthEnd < 0 ? -1 : firstLengthEnd + 1;
            int firstEnd = firstStart < 0 || firstLength < 0 ? -1 : firstStart + firstLength;
            int secondLengthEnd = firstEnd < 0 || firstEnd > pending.length()
                    ? -1 : pending.indexOf(':', firstEnd);
            int secondLength = decimal(pending, firstEnd, secondLengthEnd);
            int secondStart = secondLengthEnd < 0 ? -1 : secondLengthEnd + 1;
            int secondEnd = secondStart < 0 || secondLength < 0 ? -1 : secondStart + secondLength;
            if (firstEnd < firstStart || secondEnd < secondStart || secondEnd >= pending.length()
                    || pending.charAt(secondEnd) != ';') {
                return false;
            }
            position = secondEnd + 1;
            compared++;
            if (compared > MAX_LEXICAL_TOKENS) {
                return false;
            }

            String first = pending.substring(firstStart, firstEnd);
            String second = pending.substring(secondStart, secondEnd);
            String firstTokens = lexicalTokenStream(first);
            String secondTokens = lexicalTokenStream(second);
            int firstCursor = TOKEN_STREAM_PREFIX.length();
            int secondCursor = TOKEN_STREAM_PREFIX.length();
            int firstLimit = tokenLimit(firstTokens);
            int secondLimit = tokenLimit(secondTokens);
            int firstValueEnd = valueEndToken(first, firstTokens, firstCursor, firstLimit);
            int secondValueEnd = valueEndToken(second, secondTokens, secondCursor, secondLimit);
            if (!tokenPlanIsUsable(firstTokens) || !tokenPlanIsUsable(secondTokens)
                    || firstValueEnd < firstCursor || secondValueEnd < secondCursor
                    || nextToken(firstTokens, firstValueEnd) != firstLimit
                    || nextToken(secondTokens, secondValueEnd) != secondLimit) {
                return false;
            }

            boolean firstObject = tokenIsPunctuation(first, firstTokens, firstCursor, '{');
            boolean secondObject = tokenIsPunctuation(second, secondTokens, secondCursor, '{');
            boolean firstList = tokenIsPunctuation(first, firstTokens, firstCursor, '[');
            boolean secondList = tokenIsPunctuation(second, secondTokens, secondCursor, '[');
            if (firstObject || secondObject) {
                if (!firstObject || !secondObject) {
                    return false;
                }
                int firstObjectEnd = matchingDelimiter(first, firstTokens, firstCursor, '{', '}');
                int secondObjectEnd = matchingDelimiter(second, secondTokens, secondCursor, '{', '}');
                if (firstObjectEnd != firstValueEnd || secondObjectEnd != secondValueEnd) {
                    return false;
                }
                int firstFieldCount = 0;
                int firstField = nextToken(firstTokens, firstCursor);
                while (firstField >= TOKEN_STREAM_PREFIX.length() && firstField < firstObjectEnd) {
                    if (!tokenIsName(first, firstTokens, firstField, "")) {
                        return false;
                    }
                    String fieldName = tokenText(first, firstTokens, firstField);
                    int firstColon = nextToken(firstTokens, firstField);
                    int firstFieldValue = nextToken(firstTokens, firstColon);
                    int firstFieldValueEnd = tokenIsPunctuation(first, firstTokens, firstColon, ':')
                            ? valueEndToken(first, firstTokens, firstFieldValue, firstObjectEnd) : -1;
                    if (firstFieldValueEnd < firstFieldValue || firstFieldValueEnd >= firstObjectEnd) {
                        return false;
                    }

                    int matches = 0;
                    int secondField = nextToken(secondTokens, secondCursor);
                    while (secondField >= TOKEN_STREAM_PREFIX.length() && secondField < secondObjectEnd) {
                        if (!tokenIsName(second, secondTokens, secondField, "")) {
                            return false;
                        }
                        String secondFieldName = tokenText(second, secondTokens, secondField);
                        int secondColon = nextToken(secondTokens, secondField);
                        int secondFieldValue = nextToken(secondTokens, secondColon);
                        int secondFieldValueEnd = tokenIsPunctuation(second, secondTokens, secondColon, ':')
                                ? valueEndToken(second, secondTokens, secondFieldValue, secondObjectEnd) : -1;
                        if (secondFieldValueEnd < secondFieldValue || secondFieldValueEnd >= secondObjectEnd) {
                            return false;
                        }
                        if (fieldName.equals(secondFieldName)) {
                            matches++;
                            if (matches > 1) {
                                return false;
                            }
                            String firstNested = first.substring(tokenStart(firstTokens, firstFieldValue),
                                    tokenEnd(firstTokens, firstFieldValueEnd));
                            String secondNested = second.substring(tokenStart(secondTokens, secondFieldValue),
                                    tokenEnd(secondTokens, secondFieldValueEnd));
                            pending = pending + inputValueComparisonWorkItem(firstNested, secondNested);
                            if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                                return false;
                            }
                        }
                        secondField = nextToken(secondTokens, secondFieldValueEnd);
                    }
                    if (matches != 1 || secondField != secondObjectEnd) {
                        return false;
                    }
                    firstFieldCount++;
                    firstField = nextToken(firstTokens, firstFieldValueEnd);
                }
                if (firstField != firstObjectEnd) {
                    return false;
                }
                int secondFieldCount = 0;
                int secondField = nextToken(secondTokens, secondCursor);
                while (secondField >= TOKEN_STREAM_PREFIX.length() && secondField < secondObjectEnd) {
                    int secondColon = nextToken(secondTokens, secondField);
                    int secondFieldValue = nextToken(secondTokens, secondColon);
                    int secondFieldValueEnd = tokenIsName(second, secondTokens, secondField, "")
                            && tokenIsPunctuation(second, secondTokens, secondColon, ':')
                            ? valueEndToken(second, secondTokens, secondFieldValue, secondObjectEnd) : -1;
                    if (secondFieldValueEnd < secondFieldValue || secondFieldValueEnd >= secondObjectEnd) {
                        return false;
                    }
                    secondFieldCount++;
                    secondField = nextToken(secondTokens, secondFieldValueEnd);
                }
                if (secondField != secondObjectEnd || firstFieldCount != secondFieldCount) {
                    return false;
                }
                continue;
            }
            if (firstList || secondList) {
                if (!firstList || !secondList) {
                    return false;
                }
                int firstListEnd = matchingDelimiter(first, firstTokens, firstCursor, '[', ']');
                int secondListEnd = matchingDelimiter(second, secondTokens, secondCursor, '[', ']');
                if (firstListEnd != firstValueEnd || secondListEnd != secondValueEnd) {
                    return false;
                }
                int firstItem = nextToken(firstTokens, firstCursor);
                int secondItem = nextToken(secondTokens, secondCursor);
                while (firstItem >= TOKEN_STREAM_PREFIX.length() && firstItem < firstListEnd
                        && secondItem >= TOKEN_STREAM_PREFIX.length() && secondItem < secondListEnd) {
                    int firstItemEnd = valueEndToken(first, firstTokens, firstItem, firstListEnd);
                    int secondItemEnd = valueEndToken(second, secondTokens, secondItem, secondListEnd);
                    if (firstItemEnd < firstItem || firstItemEnd >= firstListEnd
                            || secondItemEnd < secondItem || secondItemEnd >= secondListEnd) {
                        return false;
                    }
                    String firstNested = first.substring(tokenStart(firstTokens, firstItem),
                            tokenEnd(firstTokens, firstItemEnd));
                    String secondNested = second.substring(tokenStart(secondTokens, secondItem),
                            tokenEnd(secondTokens, secondItemEnd));
                    pending = pending + inputValueComparisonWorkItem(firstNested, secondNested);
                    if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                        return false;
                    }
                    firstItem = nextToken(firstTokens, firstItemEnd);
                    secondItem = nextToken(secondTokens, secondItemEnd);
                }
                if (firstItem != firstListEnd || secondItem != secondListEnd) {
                    return false;
                }
                continue;
            }

            int firstToken = firstCursor;
            int secondToken = secondCursor;
            while (firstToken >= TOKEN_STREAM_PREFIX.length() && firstToken < firstLimit
                    && secondToken >= TOKEN_STREAM_PREFIX.length() && secondToken < secondLimit) {
                if (!tokenKind(firstTokens, firstToken).equals(tokenKind(secondTokens, secondToken))
                        || !tokenText(first, firstTokens, firstToken)
                        .equals(tokenText(second, secondTokens, secondToken))) {
                    return false;
                }
                firstToken = nextToken(firstTokens, firstToken);
                secondToken = nextToken(secondTokens, secondToken);
            }
            if (firstToken != firstLimit || secondToken != secondLimit) {
                return false;
            }
        }
        return position == pending.length();
    }

    private static String inputValueComparisonWorkItem(String first, String second) {
        return first.length() + ":" + first + second.length() + ":" + second + ";";
    }

    /** Returns whether a parsed directive begins before a source boundary. */
    public static boolean hasDirectiveBefore(String tokens, int sourceBoundary) {
        if (!tokenPlanIsUsable(tokens) || sourceBoundary < 0) {
            return false;
        }
        int indexStart = syntaxIndexStart(tokens);
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'D') {
                int first = tokens.indexOf(':', position + 1);
                if (first < 0) {
                    return false;
                }
                int start = decimal(tokens, position + 1, first);
                if (start < 0) {
                    return false;
                }
                if (start < sourceBoundary) {
                    return true;
                }
            }
            position++;
        }
        return false;
    }

    /**
     * Finds one parsed operation variable definition by name and returns its {@code '$'} source
     * offset. The plan contains the header grammar already consumed by the lexical phase, so a
     * generated routine does not need to re-walk a variable-definition list for every argument
     * or directive that references a variable.
     */
    public static int variableDefinitionStart(String source, String tokens, String expectedName) {
        if (!tokenPlanIsUsable(tokens) || source == null || expectedName == null || expectedName.length() == 0) {
            return -1;
        }
        int position = syntaxIndexStart(tokens);
        if (position < 0) {
            return -1;
        }
        // Keep this literal-cursor scalar scan portable through both Titan lowerers. Variable
        // entries are bounded by the lexical-token budget and exist only in the invocation plan.
        position = 0;
        while (position < tokens.length()) {
            if (position > syntaxIndexStart(tokens) && tokens.charAt(position) == 'V') {
                int definitionStart = syntaxIndexVariableValue(tokens, position, 0);
                int nameEnd = syntaxIndexVariableValue(tokens, position, 1);
                if (definitionStart < 0 || nameEnd <= definitionStart + 1 || nameEnd > source.length()) {
                    return -1;
                }
                if (source.substring(definitionStart + 1, nameEnd).equals(expectedName)) {
                    return definitionStart;
                }
            }
            position++;
        }
        return -1;
    }

    /** Returns the parsed type source-range start for a variable definition, or {@code -1}. */
    public static int variableTypeStart(String tokens, int definitionStart) {
        return syntaxIndexVariableValueForDefinition(tokens, definitionStart, 2);
    }

    /** Returns the parsed type source-range end for a variable definition, or {@code -1}. */
    public static int variableTypeEnd(String tokens, int definitionStart) {
        return syntaxIndexVariableValueForDefinition(tokens, definitionStart, 3);
    }

    /** Returns the parsed default-value source-range start, or {@code -1} when absent. */
    public static int variableDefaultValueStart(String tokens, int definitionStart) {
        return syntaxIndexVariableValueForDefinition(tokens, definitionStart, 4);
    }

    /** Returns the parsed default-value source-range end, or {@code -1} when absent. */
    public static int variableDefaultValueEnd(String tokens, int definitionStart) {
        return syntaxIndexVariableValueForDefinition(tokens, definitionStart, 5);
    }

    /** Returns the number of parsed variable definitions in the selected operation. */
    public static int variableDefinitionCount(String tokens) {
        if (!tokenPlanIsUsable(tokens) || syntaxIndexStart(tokens) < 0) {
            return -1;
        }
        int count = 0;
        int position = 0;
        while (position < tokens.length()) {
            if (position > syntaxIndexStart(tokens) && tokens.charAt(position) == 'X') {
                return -1;
            }
            if (position > syntaxIndexStart(tokens) && tokens.charAt(position) == 'V') {
                if (syntaxIndexVariableValue(tokens, position, 0) < 0) {
                    return -1;
                }
                count++;
                if (count >= MAX_LEXICAL_TOKENS) {
                    return -1;
                }
            }
            position++;
        }
        return count;
    }

    /** Returns the {@code '$'} source offset for the definition at the requested ordinal. */
    public static int variableDefinitionStartAt(String tokens, int ordinal) {
        if (ordinal < 0 || !tokenPlanIsUsable(tokens) || syntaxIndexStart(tokens) < 0) {
            return -1;
        }
        int index = 0;
        int position = 0;
        while (position < tokens.length()) {
            if (position > syntaxIndexStart(tokens) && tokens.charAt(position) == 'V') {
                int definitionStart = syntaxIndexVariableValue(tokens, position, 0);
                if (definitionStart < 0) {
                    return -1;
                }
                if (index == ordinal) {
                    return definitionStart;
                }
                index++;
            }
            position++;
        }
        return -1;
    }

    /** Returns the exclusive source end of one parsed variable-definition name. */
    public static int variableDefinitionNameEnd(String tokens, int definitionStart) {
        return syntaxIndexVariableValueForDefinition(tokens, definitionStart, 1);
    }

    /** Returns the number of parsed executable variable references in the selected document. */
    public static int variableReferenceCount(String tokens) {
        if (!tokenPlanIsUsable(tokens) || syntaxIndexStart(tokens) < 0) {
            return -1;
        }
        int count = 0;
        int indexStart = syntaxIndexStart(tokens);
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'U') {
                if (syntaxIndexVariableReferenceValue(tokens, position, 0) < 0) {
                    return -1;
                }
                count++;
                if (count >= MAX_LEXICAL_TOKENS) {
                    return -1;
                }
            }
            position++;
        }
        return count;
    }

    /** Returns the {@code '$'} source offset for an executable variable reference by ordinal. */
    public static int variableReferenceStartAt(String tokens, int ordinal) {
        if (ordinal < 0 || !tokenPlanIsUsable(tokens) || syntaxIndexStart(tokens) < 0) {
            return -1;
        }
        int indexStart = syntaxIndexStart(tokens);
        int index = 0;
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'U') {
                int referenceStart = syntaxIndexVariableReferenceValue(tokens, position, 0);
                if (referenceStart < 0) {
                    return -1;
                }
                if (index == ordinal) {
                    return referenceStart;
                }
                index++;
            }
            position++;
        }
        return -1;
    }

    /** Returns the exclusive source end of one parsed executable variable name. */
    public static int variableReferenceNameEnd(String tokens, int referenceStart) {
        if (!tokenPlanIsUsable(tokens) || referenceStart < 0 || syntaxIndexStart(tokens) < 0) {
            return -1;
        }
        int indexStart = syntaxIndexStart(tokens);
        int position = 0;
        while (position < tokens.length()) {
            if (position > indexStart && tokens.charAt(position) == 'U'
                    && syntaxIndexVariableReferenceValue(tokens, position, 0) == referenceStart) {
                return syntaxIndexVariableReferenceValue(tokens, position, 1);
            }
            position++;
        }
        return -1;
    }

    /**
     * Produces the selected operation plus exactly its transitively reachable named fragments.
     *
     * <p>The returned document has the same length and line boundaries as the original. Source
     * outside the selected closure is replaced with GraphQL whitespace rather than removed, so
     * every retained AST offset and GraphQL error location remains relative to the client document.
     * The closure itself is computed from the same bounded token carrier as operation selection;
     * it does not re-scan comments, strings, or delimiters in the execution bridge.</p>
     */
    public static String selectedOperationDocument(String source, String operationName) {
        String tokens = lexicalTokenStream(source);
        if (tokens.length() == 0) {
            return "";
        }
        return selectedOperationDocument(source, tokens, operationName);
    }

    /**
     * Produces the selected operation and its reachable fragment closure from an already-built
     * lexical plan. The source is retained only as the location/value carrier; token traversal,
     * including operation and fragment discovery, does not repeat lexical scanning.
     */
    public static String selectedOperationDocument(String source, String tokens, String operationName) {
        if (!tokenPlanIsUsable(tokens)) {
            return "";
        }
        String range = selectedOperationRangeFromTokens(source, tokens, operationName);
        int selectedStart = rangeStart(range);
        int selectedEnd = rangeEnd(range);
        if (selectedStart < 0 || selectedEnd <= selectedStart || selectedEnd > source.length()) {
            return "";
        }
        String pending = namedFragmentReferenceNames(source, tokens, selectedStart, selectedEnd);
        String retainedNames = ",";
        int expansions = 0;
        while (pending.length() != 0) {
            int separator = pending.indexOf(',');
            if (separator <= 0) {
                return "";
            }
            String fragmentName = pending.substring(0, separator);
            pending = pending.substring(separator + 1);
            if (commaSeparatedNameContains(retainedNames, fragmentName)) {
                continue;
            }
            String fragmentRange = namedFragmentDefinitionRange(source, tokens, fragmentName);
            int fragmentStart = rangeStart(fragmentRange);
            int fragmentEnd = rangeEnd(fragmentRange);
            if (fragmentStart < 0 || fragmentEnd <= fragmentStart || fragmentEnd > source.length()) {
                return "";
            }
            retainedNames = retainedNames + fragmentName + ",";
            expansions++;
            if (expansions > MAX_FRAGMENT_EXPANSIONS) {
                return "";
            }
            pending = pending + namedFragmentReferenceNames(source, tokens, fragmentStart, fragmentEnd);
            if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                return "";
            }
        }
        return maskedSelectedOperationDocument(
                source, tokens, selectedStart, selectedEnd, retainedNames);
    }

    /**
     * Returns whether a validated document can bypass closure masking without changing semantics.
     * A single operation with no fragment definitions is already the exact selected document;
     * any fragment definition must take the common selector so unreachable definitions are removed
     * and retained definition locations stay identical on both dialects.
     */
    public static boolean canUseSingleOperationDocumentDirectly(String source, String tokens) {
        if (selectedOperationRangeFromTokens(source, tokens, "").length() == 0) {
            return false;
        }
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < tokenLimit(tokens)) {
            if (tokenIsPunctuation(source, tokens, cursor, '{')) {
                int end = matchingDelimiter(source, tokens, cursor, '{', '}');
                if (end < 0) return false;
                cursor = nextToken(tokens, end);
                continue;
            }
            if (!tokenIsName(source, tokens, cursor, "")) {
                return false;
            }
            if (tokenIsName(source, tokens, cursor, "fragment")) {
                return false;
            }
            int header = nextToken(tokens, cursor);
            if (tokenIsName(source, tokens, header, "")) {
                header = nextToken(tokens, header);
            }
            int selection = operationSelectionToken(source, tokens, header);
            int end = matchingDelimiter(source, tokens, selection, '{', '}');
            if (end < 0) return false;
            cursor = nextToken(tokens, end);
        }
        return true;
    }

    /** Rebuilds the selected closure with every original source offset preserved. */
    private static String maskedSelectedOperationDocument(
            String source,
            String tokens,
            int selectedStart,
            int selectedEnd,
            String retainedFragmentNames
    ) {
        String selected = "";
        int sourcePosition = 0;
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < tokenLimit(tokens)) {
            int definitionStartToken = cursor;
            int definitionEndToken;
            boolean retain = false;
            if (tokenIsPunctuation(source, tokens, cursor, '{')) {
                definitionEndToken = matchingDelimiter(source, tokens, cursor, '{', '}');
            } else {
                if (!tokenIsName(source, tokens, cursor, "")) {
                    return "";
                }
                if (tokenIsName(source, tokens, cursor, "fragment")) {
                    int name = nextToken(tokens, cursor);
                    int selection = fragmentSelectionToken(source, tokens, name);
                    definitionEndToken = matchingDelimiter(source, tokens, selection, '{', '}');
                    retain = tokenIsName(source, tokens, name, "")
                            && commaSeparatedNameContains(retainedFragmentNames, tokenText(source, tokens, name));
                } else {
                    int header = nextToken(tokens, cursor);
                    if (tokenIsName(source, tokens, header, "")) {
                        header = nextToken(tokens, header);
                    }
                    int selection = operationSelectionToken(source, tokens, header);
                    definitionEndToken = matchingDelimiter(source, tokens, selection, '{', '}');
                }
            }
            if (definitionEndToken < TOKEN_STREAM_PREFIX.length()) {
                return "";
            }
            int definitionStart = tokenStart(tokens, definitionStartToken);
            int definitionEnd = tokenEnd(tokens, definitionEndToken);
            if (definitionStart < sourcePosition || definitionEnd <= definitionStart
                    || definitionEnd > source.length()) {
                return "";
            }
            if (definitionStart == selectedStart && definitionEnd == selectedEnd) {
                retain = true;
            }
            selected = selected + maskedIgnoredSource(source, sourcePosition, definitionStart);
            selected = selected + (retain
                    ? source.substring(definitionStart, definitionEnd)
                    : maskedIgnoredSource(source, definitionStart, definitionEnd));
            sourcePosition = definitionEnd;
            cursor = nextToken(tokens, definitionEndToken);
        }
        if (sourcePosition > source.length()) {
            return "";
        }
        selected = selected + maskedIgnoredSource(source, sourcePosition, source.length());
        return selected.length() == source.length() ? selected : "";
    }

    /** Replaces non-line-breaking source with spaces while retaining exact line boundaries. */
    private static String maskedIgnoredSource(String source, int start, int end) {
        if (source == null || start < 0 || end < start || end > source.length()) {
            return "";
        }
        String masked = "";
        int position = start;
        while (position < end) {
            char current = source.charAt(position);
            masked = masked + (current == '\n' || current == '\r' ? "" + current : " ");
            position++;
        }
        return masked;
    }

    /**
     * Builds a bounded scalar selection tree from the lexical stream. The tree retains source
     * locations rather than decoded values: {@code Ostart:end:kind;} records an operation selection,
     * {@code Vdefinition:nameEnd:typeStart:typeEnd:defaultStart:defaultEnd;} records an
     * operation variable definition, {@code Ureference:nameEnd;} records an executable variable
     * reference, while {@code Fstart:header:selection;} records each field and
     * {@code Afield:nameStart:nameEnd:valueStart:valueEnd;} records a field-argument value, and
     * {@code Nfield:responseStart:responseEnd:nameStart:nameEnd;} retains a field's response key
     * and actual schema name, {@code RnameStart:nameEnd:typeStart:typeEnd:directivesStart:
     * selectionStart:selectionEnd;} retains a named fragment header and selection range,
     * {@code Pspread:nameStart:nameEnd:directivesStart;} and
     * {@code Ispread:typeStart:typeEnd:directivesStart:selectionStart:selectionEnd;} retain named
     * and inline fragment spreads, and {@code CselectionStart:nodeStart:kind;} retains each direct
     * selection item in document order.
     * {@code n} denotes an absent default or leaf field, according to the record kind.
     *
     * <p>This is intentionally a syntax index, not the final typed execution plan. It gives the
     * transpilable engine parsed operation/field boundaries without an object graph and leaves
     * schema validation, input coercion, fragment expansion, and execution planning to later
     * phases.</p>
     */
    private static String selectionSyntaxIndex(String source, String tokens) {
        if (!tokenPlanIsUsable(tokens)) {
            return "";
        }
        String index = "";
        int cursor = TOKEN_STREAM_PREFIX.length();
        int operationCount = 0;
        int firstOperationSelection = -1;
        int firstOperationExecutableStart = -1;
        int limit = tokenLimit(tokens);
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < limit) {
            int selection;
            String operationKind = "";
            if (tokenIsPunctuation(source, tokens, cursor, '{')) {
                selection = cursor;
                operationKind = "q";
                operationCount++;
                if (operationCount == 1) {
                    int selectionEnd = matchingDelimiter(source, tokens, selection, '{', '}');
                    if (selectionEnd < 0) {
                        return "";
                    }
                    index = index + "O" + tokenStart(tokens, selection) + ":" + tokenStart(tokens, selectionEnd)
                            + ":" + operationKind + ";";
                    firstOperationSelection = tokenStart(tokens, selection);
                    firstOperationExecutableStart = firstOperationSelection;
                }
            } else if (tokenIsName(source, tokens, cursor, "fragment")) {
                int name = nextToken(tokens, cursor);
                selection = fragmentSelectionToken(source, tokens, name);
                int on = nextToken(tokens, name);
                int type = nextToken(tokens, on);
                int directives = nextToken(tokens, type);
                int selectionEnd = matchingDelimiter(source, tokens, selection, '{', '}');
                if (!tokenIsName(source, tokens, name, "") || !tokenIsName(source, tokens, on, "on")
                        || !tokenIsName(source, tokens, type, "") || directives < TOKEN_STREAM_PREFIX.length()
                        || selection < TOKEN_STREAM_PREFIX.length() || selectionEnd < selection) {
                    return "";
                }
                index = index + "R" + tokenStart(tokens, name) + ":" + tokenEnd(tokens, name) + ":"
                        + tokenStart(tokens, type) + ":" + tokenEnd(tokens, type) + ":"
                        + tokenStart(tokens, directives) + ":" + tokenStart(tokens, selection) + ":"
                        + tokenStart(tokens, selectionEnd) + ";";
            } else if (tokenIsName(source, tokens, cursor, "query")
                    || tokenIsName(source, tokens, cursor, "mutation")
                    || tokenIsName(source, tokens, cursor, "subscription")) {
                operationKind = tokenIsName(source, tokens, cursor, "query") ? "q"
                        : tokenIsName(source, tokens, cursor, "mutation") ? "m" : "s";
                int header = nextToken(tokens, cursor);
                if (tokenIsName(source, tokens, header, "")) {
                    header = nextToken(tokens, header);
                }
                int executableStart = operationExecutableToken(source, tokens, header);
                selection = operationSelectionToken(source, tokens, header);
                operationCount++;
                if (operationCount == 1) {
                    int selectionEnd = matchingDelimiter(source, tokens, selection, '{', '}');
                    if (selectionEnd < 0) {
                        return "";
                    }
                    index = index + "O" + tokenStart(tokens, selection) + ":" + tokenStart(tokens, selectionEnd)
                            + ":" + operationKind + ";";
                    firstOperationSelection = tokenStart(tokens, selection);
                    firstOperationExecutableStart = executableStart < TOKEN_STREAM_PREFIX.length()
                            ? -1 : tokenStart(tokens, executableStart);
                    index = appendOperationVariableSyntax(source, tokens, header, index);
                    if (index.length() == 0) {
                        // A malformed operation header is not a lexical failure. Preserve the
                        // syntactically complete selection plus a bounded error marker so the
                        // preflight layer can retain its GraphQL variable-definition diagnostic.
                        index = "O" + tokenStart(tokens, selection) + ":" + tokenStart(tokens, selectionEnd)
                                + ":" + operationKind + ";X;";
                    }
                }
            } else {
                return "";
            }
            if (selection < 0 || !tokenIsPunctuation(source, tokens, selection, '{')) {
                return "";
            }
            String result = appendSelectionSyntax(source, tokens, selection, index);
            int next = syntaxResultCursor(result);
            if (next < 0) {
                return "";
            }
            index = syntaxResultPayload(result);
            if (index.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                return "";
            }
            cursor = next;
        }
        index = appendExecutableVariableReferenceSyntax(source, tokens, firstOperationExecutableStart, index);
        return index.length() == 0 ? "" : appendDirectiveSyntax(source, tokens, index);
    }

    /**
     * Appends every directive as a source-ranged plan node. Directive argument values reuse the
     * same bounded argument-node form as fields, keyed by the directive's `@` source offset.
     */
    private static String appendDirectiveSyntax(String source, String tokens, String index) {
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < tokenLimit(tokens)) {
            if (!tokenIsPunctuation(source, tokens, cursor, '@')) {
                cursor = nextToken(tokens, cursor);
                continue;
            }
            int directiveStart = tokenStart(tokens, cursor);
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return "";
            }
            int nameStart = tokenStart(tokens, cursor);
            int nameEnd = tokenEnd(tokens, cursor);
            cursor = nextToken(tokens, cursor);
            int directiveEnd = nameEnd;
            if (tokenIsPunctuation(source, tokens, cursor, '(')) {
                int argumentsEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
                if (argumentsEnd < 0) {
                    return "";
                }
                String argumentSyntax = appendFieldArgumentSyntax(source, tokens, directiveStart,
                        cursor, argumentsEnd, index);
                int argumentCursor = syntaxResultCursor(argumentSyntax);
                if (argumentCursor < 0) {
                    return "";
                }
                index = syntaxResultPayload(argumentSyntax);
                directiveEnd = tokenEnd(tokens, argumentsEnd);
                cursor = argumentCursor;
            }
            index = index + "D" + directiveStart + ":" + directiveEnd + ":" + nameStart + ":" + nameEnd + ";";
            if (index.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                return "";
            }
        }
        return index;
    }

    /**
     * Appends all `$name` uses after selected-operation declarations and before its directives
     * or root selection. This includes operation-header directive values without mistaking a
     * variable declaration for a use.
     */
    private static String appendExecutableVariableReferenceSyntax(
            String source, String tokens, int selectionStart, String index
    ) {
        if (selectionStart < 0) {
            return "";
        }
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < tokenLimit(tokens)) {
            int sourceStart = tokenStart(tokens, cursor);
            if (sourceStart < 0) {
                return "";
            }
            if (sourceStart >= selectionStart && tokenIsPunctuation(source, tokens, cursor, '$')) {
                int name = nextToken(tokens, cursor);
                if (!tokenIsName(source, tokens, name, "")) {
                    return "";
                }
                index = index + "U" + sourceStart + ":" + tokenEnd(tokens, name) + ";";
                if (index.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                    return "";
                }
            }
            cursor = nextToken(tokens, cursor);
        }
        return index;
    }

    /** Appends parsed selected-operation variable definitions without recursive descent. */
    private static String appendOperationVariableSyntax(String source, String tokens, int header, String index) {
        if (!tokenIsPunctuation(source, tokens, header, '(')) {
            return index;
        }
        int definitionsEnd = matchingDelimiter(source, tokens, header, '(', ')');
        if (definitionsEnd < 0) {
            return "";
        }
        int cursor = nextToken(tokens, header);
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < definitionsEnd) {
            if (!tokenIsPunctuation(source, tokens, cursor, '$')) {
                return "";
            }
            int definitionStart = tokenStart(tokens, cursor);
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return "";
            }
            int nameEnd = tokenEnd(tokens, cursor);
            cursor = nextToken(tokens, cursor);
            if (!tokenIsPunctuation(source, tokens, cursor, ':')) {
                return "";
            }
            cursor = nextToken(tokens, cursor);
            int typeStart = tokenStart(tokens, cursor);
            int typeEndCursor = variableTypeEndToken(source, tokens, cursor, definitionsEnd);
            if (typeStart < 0 || typeEndCursor < TOKEN_STREAM_PREFIX.length()) {
                return "";
            }
            int typeEnd = tokenStart(tokens, typeEndCursor);
            if (typeEnd <= typeStart) {
                return "";
            }
            cursor = typeEndCursor;
            int defaultStart = -1;
            int defaultEnd = -1;
            if (tokenIsPunctuation(source, tokens, cursor, '=')) {
                cursor = nextToken(tokens, cursor);
                defaultStart = tokenStart(tokens, cursor);
                int defaultEndToken = valueEndToken(source, tokens, cursor, definitionsEnd);
                if (defaultStart < 0 || defaultEndToken < TOKEN_STREAM_PREFIX.length()) {
                    return "";
                }
                defaultEnd = tokenEnd(tokens, defaultEndToken);
                if (defaultEnd <= defaultStart) {
                    return "";
                }
                cursor = nextToken(tokens, defaultEndToken);
            }
            index = index + "V" + definitionStart + ":" + nameEnd + ":" + typeStart + ":" + typeEnd + ":"
                    + (defaultStart < 0 ? "n" : "" + defaultStart) + ":"
                    + (defaultEnd < 0 ? "n" : "" + defaultEnd) + ";";
            if (index.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                return "";
            }
        }
        return cursor == definitionsEnd ? index : "";
    }

    /** Returns the token cursor directly following a complete GraphQL variable type. */
    private static int variableTypeEndToken(String source, String tokens, int cursor, int limit) {
        int listDepth = 0;
        while (tokenIsPunctuation(source, tokens, cursor, '[')) {
            listDepth++;
            if (listDepth > MAX_GRAPHQL_DELIMITER_NESTING) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
        }
        if (!tokenIsName(source, tokens, cursor, "")) {
            return -1;
        }
        cursor = nextToken(tokens, cursor);
        if (tokenIsPunctuation(source, tokens, cursor, '!')) {
            cursor = nextToken(tokens, cursor);
        }
        while (listDepth > 0) {
            if (!tokenIsPunctuation(source, tokens, cursor, ']')) {
                return -1;
            }
            listDepth--;
            cursor = nextToken(tokens, cursor);
            if (tokenIsPunctuation(source, tokens, cursor, '!')) {
                cursor = nextToken(tokens, cursor);
            }
        }
        return cursor >= TOKEN_STREAM_PREFIX.length() && cursor <= limit ? cursor : -1;
    }

    /** Returns the final token cursor in one default-value literal. */
    private static int valueEndToken(String source, String tokens, int cursor, int limit) {
        if (cursor < TOKEN_STREAM_PREFIX.length() || cursor >= limit) {
            return -1;
        }
        if (tokenIsPunctuation(source, tokens, cursor, '[')) {
            int end = matchingDelimiter(source, tokens, cursor, '[', ']');
            return end >= cursor && end < limit ? end : -1;
        }
        if (tokenIsPunctuation(source, tokens, cursor, '{')) {
            int end = matchingDelimiter(source, tokens, cursor, '{', '}');
            return end >= cursor && end < limit ? end : -1;
        }
        if (tokenIsPunctuation(source, tokens, cursor, '$')) {
            int name = nextToken(tokens, cursor);
            return tokenIsName(source, tokens, name, "") && name < limit ? name : -1;
        }
        return tokenIsName(source, tokens, cursor, "") || tokenKind(tokens, cursor).equals("I")
                || tokenKind(tokens, cursor).equals("F") || tokenKind(tokens, cursor).equals("S")
                || tokenKind(tokens, cursor).equals("B") ? cursor : -1;
    }

    /** Parses one selection set and appends parsed field nodes to the scalar syntax index. */
    private static String appendSelectionSyntax(String source, String tokens, int selection, String index) {
        int initialEnd = matchingDelimiter(source, tokens, selection, '{', '}');
        if (initialEnd < 0) {
            return "";
        }
        // A scalar LIFO stack replaces recursive descent. Each entry holds the opening and
        // closing token cursors for one selection set. The lexer already enforces the 128-level
        // delimiter cap; the textual stack is additionally bounded before it can grow.
        String pending = selectionSyntaxStackEntry(selection, initialEnd);
        while (pending.length() != 0) {
            int entrySeparator = pending.indexOf(';');
            int entryColon = pending.indexOf(':');
            if (entryColon <= 0 || entrySeparator <= entryColon) {
                return "";
            }
            int selectionStart = decimal(pending, 0, entryColon);
            int selectionEnd = decimal(pending, entryColon + 1, entrySeparator);
            if (selectionStart < 0 || selectionEnd <= selectionStart) {
                return "";
            }
            pending = pending.substring(entrySeparator + 1);
            int cursor = nextToken(tokens, selectionStart);
            int count = 0;
            while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < selectionEnd) {
                if (tokenIsName(source, tokens, cursor, "")) {
                    int fieldStart = tokenStart(tokens, cursor);
                    int responseEnd = tokenEnd(tokens, cursor);
                    int actualNameStart = fieldStart;
                    int actualNameEnd = responseEnd;
                    cursor = nextToken(tokens, cursor);
                    if (tokenIsPunctuation(source, tokens, cursor, ':')) {
                        cursor = nextToken(tokens, cursor);
                        if (!tokenIsName(source, tokens, cursor, "")) {
                            return "";
                        }
                        actualNameStart = tokenStart(tokens, cursor);
                        actualNameEnd = tokenEnd(tokens, cursor);
                        cursor = nextToken(tokens, cursor);
                    }
                    if (tokenIsPunctuation(source, tokens, cursor, '(')) {
                        int argumentsEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
                        if (argumentsEnd < 0) {
                            return "";
                        }
                        String argumentSyntax = appendFieldArgumentSyntax(source, tokens, fieldStart,
                                cursor, argumentsEnd, index);
                        int argumentCursor = syntaxResultCursor(argumentSyntax);
                        if (argumentCursor < 0) {
                            return "";
                        }
                        index = syntaxResultPayload(argumentSyntax);
                        cursor = argumentCursor;
                    }
                    // Preserve the public field-header contract: directives remain visible to
                    // the caller, while the separately indexed child selection follows them.
                    int headerEnd = tokenStart(tokens, cursor);
                    if (headerEnd < 0) {
                        return "";
                    }
                    cursor = skipDirectiveTokens(source, tokens, cursor);
                    if (cursor < 0 || cursor > selectionEnd) {
                        return "";
                    }
                    int childSelection = -1;
                    int childEnd = -1;
                    if (tokenIsPunctuation(source, tokens, cursor, '{')) {
                        childSelection = tokenStart(tokens, cursor);
                        childEnd = matchingDelimiter(source, tokens, cursor, '{', '}');
                        if (childEnd < 0) {
                            return "";
                        }
                    }
                    index = index + "F" + fieldStart + ":" + headerEnd + ":"
                            + (childSelection < 0 ? "n" : "" + childSelection) + ";";
                    index = index + "N" + fieldStart + ":" + fieldStart + ":" + responseEnd + ":"
                            + actualNameStart + ":" + actualNameEnd + ";";
                    index = index + "C" + tokenStart(tokens, selectionStart) + ":" + fieldStart + ":F;";
                    if (index.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                        return "";
                    }
                    count++;
                    if (childSelection >= 0) {
                        pending = selectionSyntaxStackEntry(cursor, childEnd) + pending;
                        if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                            return "";
                        }
                        cursor = nextToken(tokens, childEnd);
                    }
                    continue;
                }
                if (!tokenKind(tokens, cursor).equals("D")) {
                    return "";
                }
                int spreadStart = tokenStart(tokens, cursor);
                cursor = nextToken(tokens, cursor);
                if (tokenIsName(source, tokens, cursor, "on")) {
                    cursor = nextToken(tokens, cursor);
                    if (!tokenIsName(source, tokens, cursor, "")) {
                        return "";
                    }
                    int typeStart = tokenStart(tokens, cursor);
                    int typeEnd = tokenEnd(tokens, cursor);
                    cursor = nextToken(tokens, cursor);
                    int directivesStart = tokenStart(tokens, cursor);
                    cursor = skipDirectiveTokens(source, tokens, cursor);
                    if (cursor < 0) {
                        return "";
                    }
                    if (!tokenIsPunctuation(source, tokens, cursor, '{')) {
                        return "";
                    }
                    int nestedEnd = matchingDelimiter(source, tokens, cursor, '{', '}');
                    if (nestedEnd < 0) {
                        return "";
                    }
                    index = index + "I" + spreadStart + ":" + typeStart + ":" + typeEnd + ":"
                            + directivesStart + ":" + tokenStart(tokens, cursor) + ":"
                            + tokenStart(tokens, nestedEnd) + ";";
                    index = index + "C" + tokenStart(tokens, selectionStart) + ":" + spreadStart + ":I;";
                    pending = selectionSyntaxStackEntry(cursor, nestedEnd) + pending;
                    if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                        return "";
                    }
                    cursor = nextToken(tokens, nestedEnd);
                    count++;
                    continue;
                }
                if (tokenIsPunctuation(source, tokens, cursor, '@')) {
                    int directivesStart = tokenStart(tokens, cursor);
                    cursor = skipDirectiveTokens(source, tokens, cursor);
                    if (cursor < 0 || !tokenIsPunctuation(source, tokens, cursor, '{')) {
                        return "";
                    }
                    int nestedEnd = matchingDelimiter(source, tokens, cursor, '{', '}');
                    if (nestedEnd < 0) {
                        return "";
                    }
                    index = index + "I" + spreadStart + ":n:n:" + directivesStart + ":"
                            + tokenStart(tokens, cursor) + ":" + tokenStart(tokens, nestedEnd) + ";";
                    index = index + "C" + tokenStart(tokens, selectionStart) + ":" + spreadStart + ":I;";
                    pending = selectionSyntaxStackEntry(cursor, nestedEnd) + pending;
                    if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                        return "";
                    }
                    cursor = nextToken(tokens, nestedEnd);
                    count++;
                    continue;
                }
                if (tokenIsPunctuation(source, tokens, cursor, '{')) {
                    int nestedEnd = matchingDelimiter(source, tokens, cursor, '{', '}');
                    if (nestedEnd < 0) {
                        return "";
                    }
                    index = index + "I" + spreadStart + ":n:n:" + tokenStart(tokens, cursor) + ":"
                            + tokenStart(tokens, cursor) + ":" + tokenStart(tokens, nestedEnd) + ";";
                    index = index + "C" + tokenStart(tokens, selectionStart) + ":" + spreadStart + ":I;";
                    pending = selectionSyntaxStackEntry(cursor, nestedEnd) + pending;
                    if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                        return "";
                    }
                    cursor = nextToken(tokens, nestedEnd);
                    count++;
                    continue;
                }
                if (tokenIsName(source, tokens, cursor, "")) {
                    // Named fragment spread: its definition is parsed at top level and expansion
                    // is deliberately deferred to the typed selection-plan phase.
                    int nameStart = tokenStart(tokens, cursor);
                    int nameEnd = tokenEnd(tokens, cursor);
                    cursor = nextToken(tokens, cursor);
                    int directivesStart = tokenStart(tokens, cursor);
                    cursor = skipDirectiveTokens(source, tokens, cursor);
                    if (cursor < 0) {
                        return "";
                    }
                    index = index + "P" + spreadStart + ":" + nameStart + ":" + nameEnd + ":"
                            + directivesStart + ";";
                    index = index + "C" + tokenStart(tokens, selectionStart) + ":" + spreadStart + ":P;";
                    count++;
                    continue;
                }
                if (!tokenIsPunctuation(source, tokens, cursor, '{')) {
                    return "";
                }
                int nestedEnd = matchingDelimiter(source, tokens, cursor, '{', '}');
                if (nestedEnd < 0) {
                    return "";
                }
                pending = selectionSyntaxStackEntry(cursor, nestedEnd) + pending;
                if (pending.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                    return "";
                }
                cursor = nextToken(tokens, nestedEnd);
                count++;
            }
            // An empty selection is syntactically indexable but semantically invalid. Preserve
            // it for the generated schema validator so the database returns its established
            // GraphQL "at least one field" error rather than an unrelated header error.
            if (cursor != selectionEnd) {
                return "";
            }
        }
        return nextToken(tokens, initialEnd) + "#" + index;
    }

    /** Appends complete argument-value nodes for one already-tokenized field argument list. */
    private static String appendFieldArgumentSyntax(
            String source,
            String tokens,
            int fieldStart,
            int argumentsStart,
            int argumentsEnd,
            String index
    ) {
        int cursor = nextToken(tokens, argumentsStart);
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < argumentsEnd) {
            if (!tokenIsName(source, tokens, cursor, "")) {
                return "";
            }
            int nameStart = tokenStart(tokens, cursor);
            int nameEnd = tokenEnd(tokens, cursor);
            cursor = nextToken(tokens, cursor);
            if (!tokenIsPunctuation(source, tokens, cursor, ':')) {
                return "";
            }
            cursor = nextToken(tokens, cursor);
            int valueStart = tokenStart(tokens, cursor);
            int valueEndToken = valueEndToken(source, tokens, cursor, argumentsEnd);
            if (valueStart < 0 || valueEndToken < TOKEN_STREAM_PREFIX.length()) {
                return "";
            }
            int valueEnd = tokenEnd(tokens, valueEndToken);
            if (valueEnd <= valueStart) {
                return "";
            }
            index = index + "A" + fieldStart + ":" + nameStart + ":" + nameEnd + ":" + valueStart
                    + ":" + valueEnd + ";";
            if (index.length() > MAX_FRAGMENT_WORK_CHARACTERS) {
                return "";
            }
            cursor = nextToken(tokens, valueEndToken);
        }
        return cursor == argumentsEnd ? nextToken(tokens, argumentsEnd) + "#" + index : "";
    }

    private static String selectionSyntaxStackEntry(int selectionStart, int selectionEnd) {
        return selectionStart + ":" + selectionEnd + ";";
    }

    private static int skipDirectiveTokens(String source, String tokens, int cursor) {
        while (tokenIsPunctuation(source, tokens, cursor, '@')) {
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
            if (tokenIsPunctuation(source, tokens, cursor, '(')) {
                int directivesEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
                if (directivesEnd < 0) {
                    return -1;
                }
                cursor = nextToken(tokens, directivesEnd);
            }
        }
        return cursor;
    }

    private static int syntaxResultCursor(String result) {
        int separator = result == null ? -1 : result.indexOf('#');
        return separator <= 0 ? -1 : decimal(result, 0, separator);
    }

    private static String syntaxResultPayload(String result) {
        int separator = result == null ? -1 : result.indexOf('#');
        return separator <= 0 ? "" : result.substring(separator + 1);
    }

    private static int syntaxIndexOperationSelection(String plan) {
        int position = syntaxIndexStart(plan);
        if (position < 0 || position + 2 >= plan.length() || plan.charAt(position + 1) != 'O') {
            return NO_SYNTAX_INDEX_VALUE;
        }
        int colon = plan.indexOf(':', position + 2);
        return colon < 0 ? -1 : decimal(plan, position + 2, colon);
    }

    private static int syntaxIndexFieldValue(String plan, int fieldStart, int valueIndex) {
        int indexStart = syntaxIndexStart(plan);
        if (indexStart < 0) {
            return NO_SYNTAX_INDEX_VALUE;
        }
        // Titan lowers bounded scalar scans when the traversal cursor starts at a literal
        // non-negative value and advances exactly once. Do not jump over a variable-length
        // record here: the index is bounded by the request plan, and a linear scan keeps the
        // source portable across both database lowerers.
        int position = 0;
        while (position < plan.length()) {
            if (position > indexStart && plan.charAt(position) == 'F') {
                int first = plan.indexOf(':', position + 1);
                int second = first < 0 ? -1 : plan.indexOf(':', first + 1);
                int end = second < 0 ? -1 : plan.indexOf(';', second + 1);
                if (first < 0 || second < 0 || end < 0) {
                    return -1;
                }
                int candidateStart = decimal(plan, position + 1, first);
                if (candidateStart == fieldStart) {
                    if (valueIndex == 1) {
                        return decimal(plan, first + 1, second);
                    }
                    if (valueIndex == 2 && second + 1 < end && plan.charAt(second + 1) == 'n') {
                        return -1;
                    }
                    return valueIndex == 2 ? decimal(plan, second + 1, end) : -1;
                }
            }
            position++;
        }
        return -1;
    }

    /** Reads one colon-delimited value from a bounded {@code V...;} syntax-index record. */
    private static int syntaxIndexVariableValue(String plan, int recordStart, int valueIndex) {
        if (plan == null || recordStart < 0 || recordStart >= plan.length() || plan.charAt(recordStart) != 'V'
                || valueIndex < 0 || valueIndex > 5) {
            return -1;
        }
        int valueStart = recordStart + 1;
        int index = 0;
        // Titan's scalar traversal subset requires a literal non-negative loop cursor even
        // after the record offset has been range-checked. Keep the requested record boundary in
        // the predicate instead of initializing the cursor from it.
        int position = 0;
        while (position < plan.length()) {
            if (position >= recordStart + 1) {
                char current = plan.charAt(position);
                if (current == ':' || current == ';') {
                    if (index == valueIndex) {
                        if (position == valueStart || plan.charAt(valueStart) == 'n') {
                            return -1;
                        }
                        return decimal(plan, valueStart, position);
                    }
                    if (current == ';') {
                        return -1;
                    }
                    index++;
                    valueStart = position + 1;
                }
            }
            position++;
        }
        return -1;
    }

    /** Looks up a variable-record value by its parsed {@code '$'} source offset. */
    private static int syntaxIndexVariableValueForDefinition(String plan, int definitionStart, int valueIndex) {
        if (!tokenPlanIsUsable(plan) || definitionStart < 0 || syntaxIndexStart(plan) < 0) {
            return -1;
        }
        int indexStart = syntaxIndexStart(plan);
        int position = 0;
        while (position < plan.length()) {
            if (position > indexStart && plan.charAt(position) == 'V'
                    && syntaxIndexVariableValue(plan, position, 0) == definitionStart) {
                return syntaxIndexVariableValue(plan, position, valueIndex);
            }
            position++;
        }
        return -1;
    }

    /** Reads one colon-delimited value from a bounded {@code U...;} variable-reference record. */
    private static int syntaxIndexVariableReferenceValue(String plan, int recordStart, int valueIndex) {
        if (plan == null || recordStart < 0 || recordStart >= plan.length() || plan.charAt(recordStart) != 'U'
                || valueIndex < 0 || valueIndex > 1) {
            return -1;
        }
        // As with the other scalar index readers, Titan requires a literal non-negative cursor
        // with a deterministic increment. The record offset therefore belongs in the predicate.
        int fieldStart = recordStart + 1;
        int index = 0;
        int position = 0;
        while (position < plan.length()) {
            if (position >= recordStart + 1) {
                char current = plan.charAt(position);
                if (current == ':' || current == ';') {
                    if (index == valueIndex) {
                        return position == fieldStart ? -1 : decimal(plan, fieldStart, position);
                    }
                    if (current == ';') {
                        return -1;
                    }
                    index++;
                    fieldStart = position + 1;
                }
            }
            position++;
        }
        return -1;
    }

    private static int syntaxIndexStart(String plan) {
        if (!tokenPlanIsUsable(plan)) {
            return -1;
        }
        int separator = plan.indexOf(SYNTAX_INDEX_SEPARATOR, TOKEN_STREAM_PREFIX.length());
        return separator < 0 || separator + 1 >= plan.length() ? -1 : separator;
    }

    private static String selectedOperationRangeFromTokens(String source, String tokens, String operationName) {
        String requestedName = operationName == null ? "" : operationName;
        int cursor = TOKEN_STREAM_PREFIX.length();
        int operationCount = 0;
        int selectedStart = -1;
        int selectedEnd = -1;
        while (cursor < tokenLimit(tokens)) {
            int definitionStart = cursor;
            if (tokenIsPunctuation(source, tokens, cursor, '{')) {
                int selectionEnd = matchingDelimiter(source, tokens, cursor, '{', '}');
                if (selectionEnd < 0) {
                    return "";
                }
                operationCount++;
                if (requestedName.length() == 0) {
                    selectedStart = tokenStart(tokens, cursor);
                    selectedEnd = tokenEnd(tokens, selectionEnd);
                }
                cursor = nextToken(tokens, selectionEnd);
                continue;
            }
            if (!tokenIsName(source, tokens, cursor, "")) {
                return "";
            }
            if (tokenIsName(source, tokens, cursor, "fragment")) {
                int selection = fragmentSelectionToken(source, tokens, nextToken(tokens, cursor));
                int definitionEnd = matchingDelimiter(source, tokens, selection, '{', '}');
                if (definitionEnd < 0) {
                    return "";
                }
                cursor = nextToken(tokens, definitionEnd);
                continue;
            }
            if (!tokenIsName(source, tokens, cursor, "query")
                    && !tokenIsName(source, tokens, cursor, "mutation")
                    && !tokenIsName(source, tokens, cursor, "subscription")) {
                return "";
            }
            String name = "";
            int header = nextToken(tokens, cursor);
            if (tokenIsName(source, tokens, header, "")) {
                name = tokenText(source, tokens, header);
                header = nextToken(tokens, header);
            }
            int selection = operationSelectionToken(source, tokens, header);
            int definitionEnd = matchingDelimiter(source, tokens, selection, '{', '}');
            if (definitionEnd < 0) {
                return "";
            }
            operationCount++;
            if ((requestedName.length() == 0 || requestedName.equals(name)) && selectedStart < 0) {
                selectedStart = tokenStart(tokens, definitionStart);
                selectedEnd = tokenEnd(tokens, definitionEnd);
            } else if (requestedName.length() != 0 && requestedName.equals(name)) {
                return "";
            }
            cursor = nextToken(tokens, definitionEnd);
        }
        if (selectedStart < 0 || requestedName.length() == 0 && operationCount != 1) {
            return "";
        }
        return selectedStart + ":" + selectedEnd;
    }

    /** Collects named spreads from one already-tokenized source range, excluding {@code ... on}. */
    private static String namedFragmentReferenceNames(String source, String tokens, int rangeStart, int rangeEnd) {
        String names = "";
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < tokenLimit(tokens)) {
            int start = tokenStart(tokens, cursor);
            int end = tokenEnd(tokens, cursor);
            if (start < 0 || end <= start) {
                return "";
            }
            if (start >= rangeEnd) {
                return names;
            }
            if (start >= rangeStart && tokenKind(tokens, cursor).equals("D")) {
                int nameToken = nextToken(tokens, cursor);
                if (tokenIsName(source, tokens, nameToken, "") && !tokenIsName(source, tokens, nameToken, "on")) {
                    names = names + tokenText(source, tokens, nameToken) + ",";
                }
            }
            cursor = nextToken(tokens, cursor);
        }
        return names;
    }

    /** Finds one complete, top-level named fragment definition using the tokenized document. */
    private static String namedFragmentDefinitionRange(String source, String tokens, String expectedName) {
        int cursor = TOKEN_STREAM_PREFIX.length();
        String found = "";
        while (cursor >= TOKEN_STREAM_PREFIX.length() && cursor < tokenLimit(tokens)) {
            int definitionStart = cursor;
            if (tokenIsPunctuation(source, tokens, cursor, '{')) {
                int end = matchingDelimiter(source, tokens, cursor, '{', '}');
                if (end < 0) {
                    return "";
                }
                cursor = nextToken(tokens, end);
                continue;
            }
            if (!tokenIsName(source, tokens, cursor, "")) {
                return "";
            }
            if (tokenIsName(source, tokens, cursor, "fragment")) {
                int nameToken = nextToken(tokens, cursor);
                int selection = fragmentSelectionToken(source, tokens, nameToken);
                int end = matchingDelimiter(source, tokens, selection, '{', '}');
                if (end < 0) {
                    return "";
                }
                if (tokenIsName(source, tokens, nameToken, "")
                        && tokenText(source, tokens, nameToken).equals(expectedName)) {
                    if (found.length() != 0) {
                        return "";
                    }
                    found = tokenStart(tokens, definitionStart) + ":" + tokenEnd(tokens, end);
                }
                cursor = nextToken(tokens, end);
                continue;
            }
            if (!tokenIsName(source, tokens, cursor, "query")
                    && !tokenIsName(source, tokens, cursor, "mutation")
                    && !tokenIsName(source, tokens, cursor, "subscription")) {
                return "";
            }
            int header = nextToken(tokens, cursor);
            if (tokenIsName(source, tokens, header, "")) {
                header = nextToken(tokens, header);
            }
            int selection = operationSelectionToken(source, tokens, header);
            int end = matchingDelimiter(source, tokens, selection, '{', '}');
            if (end < 0) {
                return "";
            }
            cursor = nextToken(tokens, end);
        }
        return found;
    }

    /** Returns the inclusive start source offset from a {@link #selectedOperationRange} result. */
    public static int rangeStart(String range) {
        int separator = range == null ? -1 : range.indexOf(':');
        return separator <= 0 ? -1 : decimal(range, 0, separator);
    }

    /** Returns the exclusive end source offset from a {@link #selectedOperationRange} result. */
    public static int rangeEnd(String range) {
        int separator = range == null ? -1 : range.indexOf(':');
        return separator <= 0 || separator + 1 >= range.length() ? -1 : decimal(range, separator + 1, range.length());
    }

    private static int operationSelectionToken(String source, String tokens, int cursor) {
        if (cursor < 0) {
            return -1;
        }
        if (tokenIsPunctuation(source, tokens, cursor, '(')) {
            int variablesEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
            if (variablesEnd < 0) {
                return -1;
            }
            cursor = nextToken(tokens, variablesEnd);
        }
        while (tokenIsPunctuation(source, tokens, cursor, '@')) {
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
            if (tokenIsPunctuation(source, tokens, cursor, '(')) {
                int directiveEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
                if (directiveEnd < 0) {
                    return -1;
                }
                cursor = nextToken(tokens, directiveEnd);
            }
        }
        return tokenIsPunctuation(source, tokens, cursor, '{') ? cursor : -1;
    }

    /** Returns the first executable operation-header token after an optional variable-definition list. */
    private static int operationExecutableToken(String source, String tokens, int cursor) {
        if (cursor < TOKEN_STREAM_PREFIX.length()) {
            return -1;
        }
        if (tokenIsPunctuation(source, tokens, cursor, '(')) {
            int variablesEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
            if (variablesEnd < 0) {
                return -1;
            }
            cursor = nextToken(tokens, variablesEnd);
        }
        return cursor;
    }

    private static int fragmentSelectionToken(String source, String tokens, int cursor) {
        if (!tokenIsName(source, tokens, cursor, "")) {
            return -1;
        }
        cursor = nextToken(tokens, cursor);
        if (!tokenIsName(source, tokens, cursor, "on")) {
            return -1;
        }
        cursor = nextToken(tokens, cursor);
        if (!tokenIsName(source, tokens, cursor, "")) {
            return -1;
        }
        cursor = nextToken(tokens, cursor);
        while (tokenIsPunctuation(source, tokens, cursor, '@')) {
            cursor = nextToken(tokens, cursor);
            if (!tokenIsName(source, tokens, cursor, "")) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
            if (tokenIsPunctuation(source, tokens, cursor, '(')) {
                int directiveEnd = matchingDelimiter(source, tokens, cursor, '(', ')');
                if (directiveEnd < 0) {
                    return -1;
                }
                cursor = nextToken(tokens, directiveEnd);
            }
        }
        return tokenIsPunctuation(source, tokens, cursor, '{') ? cursor : -1;
    }

    private static int matchingDelimiter(String source, String tokens, int cursor, char opening, char closing) {
        if (!tokenIsPunctuation(source, tokens, cursor, opening)) {
            return -1;
        }
        int depth = 0;
        while (cursor >= 0 && cursor < tokenLimit(tokens)) {
            if (tokenIsPunctuation(source, tokens, cursor, opening)) {
                depth++;
            } else if (tokenIsPunctuation(source, tokens, cursor, closing)) {
                depth--;
                if (depth == 0) {
                    return cursor;
                }
            }
            cursor = nextToken(tokens, cursor);
        }
        return -1;
    }

    private static boolean tokenIsName(String source, String tokens, int cursor, String expected) {
        if (cursor < TOKEN_STREAM_PREFIX.length() || cursor >= tokenLimit(tokens)) {
            return false;
        }
        int start = tokenStart(tokens, cursor);
        int end = tokenEnd(tokens, cursor);
        if (start < 0 || end <= start || !isNameStart(source.charAt(start))) {
            return false;
        }
        return expected.length() == 0 || tokenText(source, tokens, cursor).equals(expected);
    }

    private static boolean tokenIsPunctuation(String source, String tokens, int cursor, char expected) {
        if (cursor < TOKEN_STREAM_PREFIX.length() || cursor >= tokenLimit(tokens)) {
            return false;
        }
        int start = tokenStart(tokens, cursor);
        return start >= 0 && tokenEnd(tokens, cursor) == start + 1 && source.charAt(start) == expected;
    }

    private static String tokenText(String source, String tokens, int cursor) {
        int start = tokenStart(tokens, cursor);
        int end = tokenEnd(tokens, cursor);
        return start < 0 || end <= start ? "" : source.substring(start, end);
    }

    private static String tokenKind(String tokens, int cursor) {
        return cursor < TOKEN_STREAM_PREFIX.length() || cursor >= tokenLimit(tokens)
                ? "" : tokens.substring(cursor, cursor + 1);
    }

    private static int nextToken(String tokens, int cursor) {
        if (cursor < TOKEN_STREAM_PREFIX.length() || cursor >= tokenLimit(tokens)) {
            return -1;
        }
        int separator = tokens.indexOf(';', cursor);
        return separator < 0 ? -1 : separator + 1;
    }

    private static int tokenAtSourceOffset(String tokens, int sourceOffset) {
        int cursor = TOKEN_STREAM_PREFIX.length();
        while (cursor < tokenLimit(tokens)) {
            int start = tokenStart(tokens, cursor);
            if (start == sourceOffset) {
                return cursor;
            }
            if (start < 0 || start > sourceOffset) {
                return -1;
            }
            cursor = nextToken(tokens, cursor);
        }
        return -1;
    }

    private static boolean tokenPlanIsUsable(String tokens) {
        return tokens != null && tokens.length() >= TOKEN_STREAM_PREFIX.length()
                && tokens.substring(0, TOKEN_STREAM_PREFIX.length()).equals(TOKEN_STREAM_PREFIX);
    }

    /** The lexical prefix stops before the optional scalar syntax index. */
    private static int tokenLimit(String tokens) {
        if (tokens == null) {
            return 0;
        }
        int separator = tokens.indexOf(SYNTAX_INDEX_SEPARATOR, TOKEN_STREAM_PREFIX.length());
        return separator < 0 ? tokens.length() : separator;
    }

    private static int tokenStart(String tokens, int cursor) {
        int colon = tokens.indexOf(':', cursor);
        return colon < 0 ? -1 : decimal(tokens, tokenDigitsStart(tokens, cursor), colon);
    }

    private static int tokenEnd(String tokens, int cursor) {
        int colon = tokens.indexOf(':', cursor);
        int separator = colon < 0 ? -1 : tokens.indexOf(';', colon);
        return separator < 0 ? -1 : decimal(tokens, colon + 1, separator);
    }

    private static int tokenDigitsStart(String tokens, int cursor) {
        return cursor < TOKEN_STREAM_PREFIX.length() || cursor + 1 >= tokenLimit(tokens) ? -1 : cursor + 1;
    }

    private static int decimal(String value, int start, int end) {
        if (start < 0 || start >= end || end > value.length()) {
            return -1;
        }
        int result = 0;
        int position = start;
        while (position < end) {
            char current = value.charAt(position);
            if (current < '0' || current > '9' || result > 100000000) {
                return -1;
            }
            result = result * 10 + current - '0';
            position++;
        }
        return result;
    }

    private static char graphqlClosingDelimiter(char opening) {
        if (opening == '(') return ')';
        if (opening == '[') return ']';
        return '}';
    }

    private static int graphqlNumberEnd(String source, int start, int limit) {
        int position = start;
        if (source.charAt(position) == '-') {
            position++;
            if (position >= limit) return -1;
        }
        if (source.charAt(position) == '0') {
            position++;
            if (position < limit && source.charAt(position) >= '0' && source.charAt(position) <= '9') return -1;
        } else if (source.charAt(position) >= '1' && source.charAt(position) <= '9') {
            position++;
            while (position < limit && source.charAt(position) >= '0' && source.charAt(position) <= '9') position++;
        } else {
            return -1;
        }
        if (position < limit && source.charAt(position) == '.') {
            position++;
            int fractionalStart = position;
            while (position < limit && source.charAt(position) >= '0' && source.charAt(position) <= '9') position++;
            if (position == fractionalStart) return -1;
        }
        if (position < limit && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
            position++;
            if (position < limit && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
            int exponentStart = position;
            while (position < limit && source.charAt(position) >= '0' && source.charAt(position) <= '9') position++;
            if (position == exponentStart) return -1;
        }
        return position < limit && isNameStart(source.charAt(position)) ? -1 : position;
    }

    private static boolean graphqlNumberIsFloat(String source, int start, int end) {
        int position = start;
        while (position < end) {
            char current = source.charAt(position);
            if (current == '.' || current == 'e' || current == 'E') return true;
            position++;
        }
        return false;
    }

    /** Returns the exclusive end of one lexically valid normal or block GraphQL string. */
    public static int graphqlStringEnd(String source, int start, int limit) {
        boolean block = start + 2 < limit && source.charAt(start + 1) == '"' && source.charAt(start + 2) == '"';
        int position = start + (block ? 3 : 1);
        while (position < limit) {
            char current = source.charAt(position);
            if (block) {
                // GraphQL block strings escape a triple quote as `\"""`. Treat the entire
                // four-character sequence as content before looking for a closing delimiter;
                // advancing one character would incorrectly reopen the overlapping second
                // quote as a terminator when the escaped triple is followed by the real close.
                if (current == '\\' && position + 3 < limit && source.charAt(position + 1) == '"'
                        && source.charAt(position + 2) == '"' && source.charAt(position + 3) == '"') {
                    position += 4;
                    continue;
                }
                if (current == '"' && position + 2 < limit && source.charAt(position + 1) == '"'
                        && source.charAt(position + 2) == '"') return position + 3;
                position++;
                continue;
            }
            if (current == '"') return position + 1;
            if (current < ' ') return -1;
            if (current == '\\') {
                position++;
                if (position >= limit) return -1;
                char escaped = source.charAt(position);
                if (escaped == 'u') {
                    if (position + 4 >= limit || !graphqlHex(source.charAt(position + 1))
                            || !graphqlHex(source.charAt(position + 2)) || !graphqlHex(source.charAt(position + 3))
                            || !graphqlHex(source.charAt(position + 4))) return -1;
                    position += 5;
                    continue;
                }
                if (escaped != '"' && escaped != '\\' && escaped != '/' && escaped != 'b'
                        && escaped != 'f' && escaped != 'n' && escaped != 'r' && escaped != 't') return -1;
            }
            position++;
        }
        return -1;
    }

    private static boolean graphqlPunctuation(char current) {
        return current == '!' || current == '$' || current == '&' || current == '(' || current == ')'
                || current == ':' || current == '=' || current == '@' || current == '[' || current == ']'
                || current == '{' || current == '}' || current == '|';
    }

    private static boolean commaSeparatedNameContains(String names, String expected) {
        if (names == null || expected == null || expected.length() == 0) {
            return false;
        }
        String wanted = "," + expected + ",";
        String padded = names.charAt(0) == ',' ? names : "," + names + ",";
        int position = 0;
        int limit = padded.length() - wanted.length();
        while (position <= limit) {
            if (padded.substring(position, position + wanted.length()).equals(wanted)) {
                return true;
            }
            position++;
        }
        return false;
    }

    private static boolean graphqlHex(char current) {
        return current >= '0' && current <= '9' || current >= 'a' && current <= 'f'
                || current >= 'A' && current <= 'F';
    }

    private static boolean isIgnored(char current) {
        return current == ' ' || current == '\n' || current == '\r' || current == '\t' || current == '\ufeff';
    }

    private static boolean isNameStart(char current) {
        return current == '_' || current >= 'A' && current <= 'Z' || current >= 'a' && current <= 'z';
    }

    private static boolean isNamePart(char current) {
        return isNameStart(current) || current >= '0' && current <= '9';
    }

    private static int nameEnd(String source, int start, int limit) {
        int position = start;
        while (position < limit && isNamePart(source.charAt(position))) position++;
        return position;
    }
}
