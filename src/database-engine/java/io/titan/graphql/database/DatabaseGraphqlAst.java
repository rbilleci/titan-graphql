package io.titan.graphql.database;

/**
 * Bounded, invocation-local typed AST carrier for executable GraphQL documents.
 *
 * <p>The representation deliberately uses scalar records instead of a Java object graph so
 * Titan can lower it on both database dialects. Unlike the legacy syntax index, every emitted
 * executable node has a stable node kind, a source location, and an explicit direct-selection
 * parent. It is a language-core artifact: it neither knows a model nor executes a resolver.</p>
 *
 * <p>Records have the form {@code Nkind:start:parent:payload;}. {@code parent} is a selection-set
 * opening offset or {@code n}. Kinds are {@code o} (operation), {@code v} (variable definition),
 * {@code u} (executable variable reference), {@code a} (argument value), {@code d} (directive),
 * {@code r} (fragment definition), and
 * {@code f}/{@code p}/{@code i} (field, named spread, and inline spread). Field payloads retain
     * header, child-selection, response-key, and schema-name ranges plus the field's direct argument
     * count. An {@code e} node is a retained syntax error from the lexical/syntax phase. Payloads
     * preserve the narrow source ranges needed by the following typed validation and plan phases.</p>
 */
public final class DatabaseGraphqlAst {

    private static final String PREFIX = "ast1;";
    private static final String EXECUTABLE_DIRECTIVE_DESCRIPTOR_PREFIX = "ed1|";
    private static final String EXECUTABLE_DIRECTIVE_SCHEMA_RECORD = "Ns:0:n:";
    private static final int MAX_NODES = 8192;
    // Keep this aligned with DatabaseGraphqlEngine's bounded execution-selection contract. A
    // direct-plan result at the limit is deliberately refused so it cannot hide further work.
    private static final int MAX_DIRECT_SELECTION_FIELDS = 32;

    private DatabaseGraphqlAst() {
    }

    /**
     * Attaches generator-owned executable-directive semantics to an invocation-local AST.
     *
     * <p>The descriptor contains only GraphQL names and compact behavior/location markers, so it
     * cannot contain the AST record terminator. Carrying it in the request plan lets every nested
     * selection walker consume the same registered semantics without globals, a JVM callback, or
     * a parallel query rewrite.</p>
     */
    public static String withExecutableDirectiveDescriptor(String ast, String descriptor) {
        if (!hasPrefix(ast) || descriptor == null
                || !descriptor.startsWith(EXECUTABLE_DIRECTIVE_DESCRIPTOR_PREFIX)
                || descriptor.indexOf(';') >= 0
                || executableDirectiveDescriptor(ast).length() != 0
                || ast.length() + descriptor.length() + EXECUTABLE_DIRECTIVE_SCHEMA_RECORD.length() + 1 > 262144) {
            return "";
        }
        return ast + EXECUTABLE_DIRECTIVE_SCHEMA_RECORD + descriptor + ";";
    }

    /** Returns the attached executable-directive descriptor, or empty when absent/malformed. */
    public static String executableDirectiveDescriptor(String ast) {
        if (!hasPrefix(ast)) {
            return "";
        }
        String marker = ";" + EXECUTABLE_DIRECTIVE_SCHEMA_RECORD;
        int start = ast.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start = start + marker.length();
        int end = ast.indexOf(';', start);
        if (end <= start || end != ast.length() - 1) {
            return "";
        }
        String descriptor = ast.substring(start, end);
        return descriptor.startsWith(EXECUTABLE_DIRECTIVE_DESCRIPTOR_PREFIX) ? descriptor : "";
    }

    /** Parses one document through the shared lexical/syntax core into the typed AST carrier. */
    public static String parse(String source) {
        return parse(source, DatabaseGraphqlLanguage.documentPlan(source));
    }

    /**
     * Converts an already-built language plan into the typed AST carrier without re-lexing source.
     * The caller supplies only invocation-local data; an empty result is an invalid carrier.
     */
    public static String parse(String source, String languagePlan) {
        if (source == null || languagePlan == null) {
            return "";
        }
        String operationKind = DatabaseGraphqlLanguage.operationKind(languagePlan);
        int rootSelection = DatabaseGraphqlLanguage.firstOperationSelectionStart(source, languagePlan);
        int indexStart = languagePlan.indexOf('|');
        if (operationKind.length() == 0 || rootSelection < 0 || indexStart < 0) {
            return "";
        }
        String ast = PREFIX + "No:" + rootSelection + ":n:" + operationKind + ";";
        int nodes = 1;
        // The scalar syntax index emits F (field shape), then N (field identity), then C
        // (selection parent) for every field. Retain those three facts until C joins them into
        // one typed field node; no field lookup needs to return to the syntax index afterwards.
        int retainedFieldStart = -1;
        int retainedFieldHeaderEnd = -1;
        int retainedFieldChildSelection = -1;
        int retainedIdentityFieldStart = -1;
        int retainedResponseStart = -1;
        int retainedResponseEnd = -1;
        int retainedNameStart = -1;
        int retainedNameEnd = -1;
        int retainedArgumentOwner = -1;
        int retainedArgumentCount = 0;
        int retainedFieldArgumentCount = 0;
        int recordStart = 0;
        int position = 0;
        while (position < languagePlan.length()) {
            char current = languagePlan.charAt(position);
            if (position == indexStart) {
                recordStart = position + 1;
            } else if (position > indexStart && current == ';') {
                int end = position;
                if (end <= recordStart) {
                    return "";
                }
                char kind = languagePlan.charAt(recordStart);
                if (kind == 'X') {
                    return PREFIX + "Ne:" + rootSelection + ":n:syntax;";
                }
                if (kind == 'F') {
                    int first = languagePlan.indexOf(':', recordStart + 1);
                    int second = first < 0 ? -1 : languagePlan.indexOf(':', first + 1);
                    retainedFieldStart = first < 0 ? -1 : decimal(languagePlan, recordStart + 1, first);
                    retainedFieldHeaderEnd = second < 0 ? -1 : decimal(languagePlan, first + 1, second);
                    retainedFieldArgumentCount = retainedArgumentOwner == retainedFieldStart
                            ? retainedArgumentCount : 0;
                    if (retainedFieldStart < 0 || retainedFieldHeaderEnd < retainedFieldStart
                            || retainedFieldHeaderEnd > source.length() || second < 0 || second >= end) {
                        return "";
                    }
                    String child = languagePlan.substring(second + 1, end);
                    retainedFieldChildSelection = child.equals("n") ? -1 : decimal(child, 0, child.length());
                    if ((retainedFieldChildSelection < 0 && child.equals("n") == false)
                            || retainedFieldChildSelection >= source.length()) {
                        return "";
                    }
                } else if (kind == 'N') {
                    int first = languagePlan.indexOf(':', recordStart + 1);
                    int second = first < 0 ? -1 : languagePlan.indexOf(':', first + 1);
                    int third = second < 0 ? -1 : languagePlan.indexOf(':', second + 1);
                    int fourth = third < 0 ? -1 : languagePlan.indexOf(':', third + 1);
                    retainedIdentityFieldStart = first < 0 ? -1 : decimal(languagePlan, recordStart + 1, first);
                    retainedResponseStart = second < 0 ? -1 : decimal(languagePlan, first + 1, second);
                    retainedResponseEnd = third < 0 ? -1 : decimal(languagePlan, second + 1, third);
                    retainedNameStart = fourth < 0 ? -1 : decimal(languagePlan, third + 1, fourth);
                    retainedNameEnd = fourth < 0 ? -1 : decimal(languagePlan, fourth + 1, end);
                    if (retainedIdentityFieldStart < 0 || retainedResponseStart < 0
                            || retainedResponseEnd <= retainedResponseStart || retainedNameStart < 0
                            || retainedNameEnd <= retainedNameStart || retainedResponseEnd > source.length()
                            || retainedNameEnd > source.length()) {
                        return "";
                    }
                } else if (kind == 'V' || kind == 'U' || kind == 'R' || kind == 'D') {
                    int first = languagePlan.indexOf(':', recordStart + 1);
                    int start = first < 0 ? -1 : decimal(languagePlan, recordStart + 1, first);
                    if (start < 0 || first >= end) {
                        return "";
                    }
                    char astKind = kind == 'V' ? 'v' : kind == 'U' ? 'u' : kind == 'R' ? 'r' : 'd';
                    ast = ast + "N" + astKind + ":" + start + ":n:"
                            + languagePlan.substring(first + 1, end) + ";";
                    nodes++;
                } else if (kind == 'A') {
                    int first = languagePlan.indexOf(':', recordStart + 1);
                    int second = first < 0 ? -1 : languagePlan.indexOf(':', first + 1);
                    int third = second < 0 ? -1 : languagePlan.indexOf(':', second + 1);
                    int fourth = third < 0 ? -1 : languagePlan.indexOf(':', third + 1);
                    int owner = first < 0 ? -1 : decimal(languagePlan, recordStart + 1, first);
                    int valueStart = fourth < 0 ? -1 : decimal(languagePlan, third + 1, fourth);
                    if (owner < 0 || valueStart < 0 || fourth >= end) {
                        return "";
                    }
                    if (owner == retainedArgumentOwner) {
                        retainedArgumentCount++;
                    } else {
                        retainedArgumentOwner = owner;
                        retainedArgumentCount = 1;
                    }
                    if (retainedArgumentCount > MAX_NODES) {
                        return "";
                    }
                    ast = ast + "Na:" + valueStart + ":" + owner + ":"
                            + languagePlan.substring(first + 1, end) + ";";
                    nodes++;
                } else if (kind == 'C') {
                    int first = languagePlan.indexOf(':', recordStart + 1);
                    int second = first < 0 ? -1 : languagePlan.indexOf(':', first + 1);
                    int parent = first < 0 ? -1 : decimal(languagePlan, recordStart + 1, first);
                    int nodeStart = second < 0 ? -1 : decimal(languagePlan, first + 1, second);
                    if (parent < 0 || nodeStart < 0 || end != second + 2) {
                        return "";
                    }
                    char selectionKind = languagePlan.charAt(second + 1);
                    if (selectionKind == 'F') {
                        if (retainedFieldStart != nodeStart || retainedIdentityFieldStart != nodeStart
                                || retainedFieldHeaderEnd < nodeStart || retainedResponseStart != nodeStart
                                || retainedResponseEnd <= retainedResponseStart
                                || retainedNameEnd <= retainedNameStart) {
                            return "";
                        }
                        ast = ast + "Nf:" + nodeStart + ":" + parent + ":" + retainedFieldHeaderEnd + ":"
                                + (retainedFieldChildSelection < 0 ? "n" : "" + retainedFieldChildSelection) + ":"
                                + retainedResponseStart + ":" + retainedResponseEnd + ":" + retainedNameStart
                                + ":" + retainedNameEnd + ":" + retainedFieldArgumentCount + ";";
                    } else if (selectionKind == 'P') {
                        String spread = DatabaseGraphqlLanguage.namedFragmentSpreadInfo(languagePlan, nodeStart);
                        if (spread.length() == 0) {
                            return "";
                        }
                        ast = ast + "Np:" + nodeStart + ":" + parent + ":" + spread + ";";
                    } else if (selectionKind == 'I') {
                        String spread = DatabaseGraphqlLanguage.inlineFragmentInfo(languagePlan, nodeStart);
                        if (spread.length() == 0) {
                            return "";
                        }
                        ast = ast + "Ni:" + nodeStart + ":" + parent + ":" + spread + ";";
                    } else {
                        return "";
                    }
                    nodes++;
                }
                if (nodes > MAX_NODES || ast.length() > 262144) {
                    return "";
                }
                recordStart = end + 1;
            }
            position++;
        }
        if (recordStart != languagePlan.length()) {
            return "";
        }
        return ast;
    }

    /** Returns the number of typed nodes, or {@code -1} for an invalid AST carrier. */
    public static int nodeCount(String ast) {
        if (!hasPrefix(ast)) {
            return -1;
        }
        int position = PREFIX.length();
        int count = 0;
        while (position < ast.length()) {
            int end = ast.indexOf(';', position);
            if (end <= position || !ast.substring(position, position + 1).equals("N")
                    || nodeFirstColon(ast, position, end) < 0) {
                return -1;
            }
            count++;
            if (count > MAX_NODES) {
                return -1;
            }
            position = end + 1;
        }
        return count;
    }

    /**
     * Counts every retained executable field node in one selected-operation AST. This is a
     * request-global measure: fields in reachable fragment definitions count as work even when a
     * runtime directive later excludes them, because planning and validation still traverse
     * them. The scalar scan deliberately avoids repeatedly resolving node ordinals while the
     * database preflight enforces its semantic-work budget.
     */
    public static int executableFieldCount(String ast) {
        if (!hasPrefix(ast)) {
            return -1;
        }
        int position = PREFIX.length();
        int count = 0;
        while (position < ast.length()) {
            int end = ast.indexOf(';', position);
            if (end <= position || !ast.substring(position, position + 1).equals("N")
                    || nodeFirstColon(ast, position, end) < 0) {
                return -1;
            }
            String kind = ast.substring(position + 1, position + 2);
            if (kind.equals("f")) {
                count++;
                if (count > MAX_NODES) {
                    return -1;
                }
            }
            position = end + 1;
        }
        return count;
    }

    /** Returns the selected operation's root selection start, or {@code -1} for an invalid AST. */
    public static int operationSelectionStart(String ast) {
        if (ast == null || ast.indexOf(PREFIX) != 0) {
            return -1;
        }
        int recordStart = PREFIX.length();
        int end = ast.indexOf(';', recordStart);
        if (end <= recordStart + 3 || !ast.substring(recordStart, recordStart + 3).equals("No:")) {
            return -1;
        }
        int separator = ast.indexOf(':', recordStart + 3);
        if (separator < 0 || separator >= end) {
            return -1;
        }
        return decimal(ast, recordStart + 3, separator);
    }

    /** Returns the selected operation kind retained in the AST, or empty for an invalid carrier. */
    public static String operationKind(String ast) {
        if (ast == null || ast.indexOf(PREFIX) != 0) {
            return "";
        }
        int recordStart = PREFIX.length();
        int end = ast.indexOf(';', recordStart);
        int first = end < 0 ? -1 : ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        if (end <= recordStart || first != recordStart + 2 || second < 0 || third < 0 || third >= end
                || !ast.substring(recordStart, first).equals("No")
                || !ast.substring(second + 1, third).equals("n")
                || decimal(ast, first + 1, second) < 0) {
            return "";
        }
        String kind = ast.substring(third + 1, end);
        return kind.equals("query") || kind.equals("mutation") || kind.equals("subscription") ? kind : "";
    }

    /**
     * Counts direct executable selection items for one selection-set opening offset.
     *
     * <p>This is deliberately a narrow AST traversal primitive rather than a generic node
     * iterator. Fragment expansion is the only current consumer, and directly scanning its
     * three executable node shapes keeps the transpiled MySQL routine closure below the stored
     * program cache budget.</p>
     */
    public static int selectionItemCount(String ast, int selectionStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || selectionStart < 0) {
            return -1;
        }
        int count = 0;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            if (end > recordStart + 3) {
                String kind = ast.substring(recordStart + 1, recordStart + 2);
                if (kind.equals("f") || kind.equals("p") || kind.equals("i")) {
                    int first = ast.indexOf(':', recordStart + 2);
                    int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                    int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                    if (first != recordStart + 2 || second < 0 || third < 0 || third >= end
                            || decimal(ast, first + 1, second) < 0) {
                        return -1;
                    }
                    int parent = decimal(ast, second + 1, third);
                    if (parent < 0) {
                        return -1;
                    }
                    if (parent == selectionStart) {
                        count++;
                        if (count > MAX_NODES) {
                            return -1;
                        }
                    }
                }
            }
            recordStart = end + 1;
        }
        return count;
    }

    /**
     * Returns one direct selection item as {@code sourceStart:F|P|I:payload}.
     *
     * <p>For named and inline spreads the payload is intentionally the same bounded numeric
     * carrier formerly read from the syntax index. The caller can therefore migrate traversal to
     * the typed AST without changing directive/type-condition validation all at once.</p>
     */
    public static String selectionItemInfo(String ast, int selectionStart, int ordinal) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || selectionStart < 0 || ordinal < 0) {
            return "";
        }
        int item = 0;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            if (end > recordStart + 3) {
                String kind = ast.substring(recordStart + 1, recordStart + 2);
                if (kind.equals("f") || kind.equals("p") || kind.equals("i")) {
                    int first = ast.indexOf(':', recordStart + 2);
                    int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                    int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                    if (first != recordStart + 2 || second < 0 || third < 0 || third >= end) {
                        return "";
                    }
                    int sourceStart = decimal(ast, first + 1, second);
                    int parent = decimal(ast, second + 1, third);
                    if (sourceStart < 0 || parent < 0) {
                        return "";
                    }
                    if (parent == selectionStart) {
                        if (item == ordinal) {
                            String payload = ast.substring(third + 1, end);
                            if (payload.length() == 0) {
                                return "";
                            }
                            String uppercaseKind = kind.equals("f") ? "F" : kind.equals("p") ? "P" : "I";
                            return sourceStart + ":" + uppercaseKind + ":" + payload;
                        }
                        item++;
                    }
                }
            }
            recordStart = end + 1;
        }
        return "";
    }

    /**
     * Returns a bounded direct-field plan ({@code v1;offset;...}) for one selection set without
     * spreads, {@code fragment;} when a spread requires the fragment-aware walker, or empty for
     * an invalid carrier. This keeps the no-fragment path proportional to the selected items: it
     * seeks each direct-parent marker instead of interpreting every AST record once per nested
     * selection. The typed carrier remains the sole source of executable topology.
     */
    public static String directFieldSelectionPlan(String ast, int selectionStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || selectionStart < 0) {
            return "";
        }
        String plan = "v1;";
        String parentMarker = ":" + selectionStart + ":";
        int count = 0;
        int searchStart = PREFIX.length();
        while (searchStart < ast.length()) {
            int parentMarkerStart = ast.indexOf(parentMarker, searchStart);
            if (parentMarkerStart < 0) {
                return plan;
            }
            // A numeric payload can contain the same characters as a parent marker. Locate the
            // enclosing record and accept the hit only when it is the second separator of an
            // executable selection record. This preserves the carrier's fail-closed boundaries
            // without requiring a whole-AST scan for each nested selection.
            int delimiter = parentMarkerStart - 1;
            while (delimiter >= PREFIX.length() - 1 && ast.charAt(delimiter) != ';') {
                delimiter--;
            }
            int recordStart = delimiter + 1;
            int end = ast.indexOf(';', parentMarkerStart + parentMarker.length());
            if (recordStart < PREFIX.length() || end <= recordStart) {
                return "";
            }
            String kind = end > recordStart + 3 ? ast.substring(recordStart + 1, recordStart + 2) : "";
            int first = ast.indexOf(':', recordStart + 2);
            int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
            if (!(kind.equals("f") || kind.equals("p") || kind.equals("i"))
                    || first != recordStart + 2 || second != parentMarkerStart) {
                searchStart = parentMarkerStart + 1;
                continue;
            }
            int third = ast.indexOf(':', second + 1);
            int sourceStart = decimal(ast, first + 1, second);
            if (third < 0 || third >= end || sourceStart < 0) {
                return "";
            }
            if (!kind.equals("f")) {
                return "fragment;";
            }
            count++;
            if (count >= MAX_DIRECT_SELECTION_FIELDS) {
                return "";
            }
            plan = plan + sourceStart + ";";
            searchStart = end + 1;
        }
        return plan;
    }

    /**
     * Returns a field's retained metadata as
     * {@code headerEnd:childSelection|n:responseStart:responseEnd:nameStart:nameEnd}.
     *
     * <p>The response and schema-name ranges are distinct for aliases. This is the typed-AST
     * replacement for the legacy field-header/selection/identity syntax-index lookups.</p>
     */
    public static String fieldInfo(String ast, int fieldStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || fieldStart < 0) {
            return "";
        }
        // Field records are keyed by their unique source offset. The former implementation
        // walked every AST record on each metadata read; generated introspection reuses field
        // metadata heavily, turning a valid broad request into quadratic database work. Anchoring
        // the lookup to a record delimiter keeps it unambiguous while letting both SQL lowerers
        // use their optimized string-position primitive.
        int marker = ast.indexOf(";Nf:" + fieldStart + ":");
        if (marker < 0) {
            return "";
        }
        int recordStart = marker + 1;
        int end = ast.indexOf(';', recordStart);
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
        int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
        int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
        int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
        int eighth = seventh < 0 ? -1 : ast.indexOf(':', seventh + 1);
        int ninth = eighth < 0 ? -1 : ast.indexOf(':', eighth + 1);
        if (end <= recordStart || first != recordStart + 2 || second < 0 || third < 0 || fourth < 0
                || fifth < 0 || sixth < 0 || seventh < 0 || eighth < 0 || ninth < 0 || ninth >= end) {
            return "";
        }
        int sourceStart = decimal(ast, first + 1, second);
        int parent = decimal(ast, second + 1, third);
        int headerEnd = decimal(ast, third + 1, fourth);
        String child = ast.substring(fourth + 1, fifth);
        int childSelection = child.equals("n") ? -1 : decimal(child, 0, child.length());
        int responseStart = decimal(ast, fifth + 1, sixth);
        int responseEnd = decimal(ast, sixth + 1, seventh);
        int nameStart = decimal(ast, seventh + 1, eighth);
        int nameEnd = decimal(ast, eighth + 1, ninth);
        int argumentCount = decimal(ast, ninth + 1, end);
        if (sourceStart != fieldStart || parent < 0 || headerEnd < sourceStart
                || (childSelection < 0 && child.equals("n") == false)
                || responseStart != sourceStart || responseEnd <= responseStart
                || nameStart < responseStart || nameEnd <= nameStart
                || argumentCount < 0 || argumentCount > MAX_NODES) {
            return "";
        }
        return ast.substring(third + 1, ninth);
    }

    /**
     * Reads one validated field-metadata slot without allocating the six-slot transport string
     * returned by {@link #fieldInfo(String, int)}. Generated execution repeatedly needs a single
     * offset at a time; preserving that representation internally avoids a second parser pass in
     * the PostgreSQL/MySQL routine closure.
     */
    public static int fieldInfoValue(String ast, int fieldStart, int slot) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || fieldStart < 0 || slot < 0 || slot > 5) {
            return -1;
        }
        int marker = ast.indexOf(";Nf:" + fieldStart + ":");
        if (marker < 0) {
            return -1;
        }
        int recordStart = marker + 1;
        int end = ast.indexOf(';', recordStart);
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
        int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
        int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
        int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
        int eighth = seventh < 0 ? -1 : ast.indexOf(':', seventh + 1);
        int ninth = eighth < 0 ? -1 : ast.indexOf(':', eighth + 1);
        if (end <= recordStart || first != recordStart + 2 || second < 0 || third < 0 || fourth < 0
                || fifth < 0 || sixth < 0 || seventh < 0 || eighth < 0 || ninth < 0 || ninth >= end) {
            return -1;
        }
        int sourceStart = decimal(ast, first + 1, second);
        int parent = decimal(ast, second + 1, third);
        int headerEnd = decimal(ast, third + 1, fourth);
        String child = ast.substring(fourth + 1, fifth);
        int childSelection = child.equals("n") ? -1 : decimal(child, 0, child.length());
        int responseStart = decimal(ast, fifth + 1, sixth);
        int responseEnd = decimal(ast, sixth + 1, seventh);
        int nameStart = decimal(ast, seventh + 1, eighth);
        int nameEnd = decimal(ast, eighth + 1, ninth);
        int argumentCount = decimal(ast, ninth + 1, end);
        if (sourceStart != fieldStart || parent < 0 || headerEnd < sourceStart
                || (childSelection < 0 && child.equals("n") == false)
                || responseStart != sourceStart || responseEnd <= responseStart
                || nameStart < responseStart || nameEnd <= nameStart
                || argumentCount < 0 || argumentCount > MAX_NODES) {
            return -1;
        }
        if (slot == 0) return headerEnd;
        if (slot == 1) return childSelection;
        if (slot == 2) return responseStart;
        if (slot == 3) return responseEnd;
        if (slot == 4) return nameStart;
        return nameEnd;
    }

    /**
     * Counts arguments retained for a typed field node.  The AST stores each argument separately
     * because directives use the same argument shape; the direct-parent slot is therefore the
     * field (or directive) source offset rather than a positional child list.
     *
     * <p>Returns {@code -1} if the carrier contains a malformed argument record.  This lets the
     * generated binding keep its existing fail-closed unknown/duplicate argument gate without
     * reopening the lexical syntax index.</p>
     */
    public static int fieldArgumentCount(String ast, int fieldStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || fieldStart < 0) {
            return -1;
        }
        int marker = ast.indexOf(";Nf:" + fieldStart + ":");
        if (marker < 0) {
            // Executable directives use the same argument accessor but are not field records.
            // They are comparatively rare, so retain the bounded carrier scan for that owner
            // kind while keeping the common field path keyed and constant-work.
            int count = 0;
            int argumentRecordStart = PREFIX.length();
            while (argumentRecordStart < ast.length()) {
                int argumentRecordEnd = ast.indexOf(';', argumentRecordStart);
                if (argumentRecordEnd <= argumentRecordStart
                        || !ast.substring(argumentRecordStart, argumentRecordStart + 1).equals("N")) {
                    return -1;
                }
                if (argumentRecordEnd > argumentRecordStart + 2
                        && ast.substring(argumentRecordStart, argumentRecordStart + 2).equals("Na")) {
                    int owner = argumentOwner(ast, argumentRecordStart, argumentRecordEnd);
                    if (owner < 0 || argumentInfo(ast, argumentRecordStart, argumentRecordEnd).length() == 0) {
                        return -1;
                    }
                    if (owner == fieldStart) {
                        count++;
                        if (count > MAX_NODES) {
                            return -1;
                        }
                    }
                }
                argumentRecordStart = argumentRecordEnd + 1;
            }
            return count;
        }
        int recordStart = marker + 1;
        int end = ast.indexOf(';', recordStart);
        int position = recordStart + 2;
        int separator = -1;
        int slot = 0;
        while (slot < 9) {
            separator = ast.indexOf(':', position);
            if (separator < 0 || separator >= end) {
                return -1;
            }
            position = separator + 1;
            slot++;
        }
        int sourceStart = decimal(ast, recordStart + 3, ast.indexOf(':', recordStart + 3));
        int count = decimal(ast, position, end);
        return sourceStart == fieldStart && count >= 0 && count <= MAX_NODES ? count : -1;
    }

    /**
     * Returns one field argument as {@code nameStart:nameEnd:valueStart:valueEnd}, in source
     * order, or an empty value when the requested argument does not exist or is malformed.
     */
    public static String fieldArgumentInfo(String ast, int fieldStart, int ordinal) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || fieldStart < 0 || ordinal < 0) {
            return "";
        }
        int count = fieldArgumentCount(ast, fieldStart);
        if (count < 0 || ordinal >= count) {
            return "";
        }
        int item = 0;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Na")) {
                int owner = argumentOwner(ast, recordStart, end);
                String info = argumentInfo(ast, recordStart, end);
                if (owner < 0 || info.length() == 0) {
                    return "";
                }
                if (owner == fieldStart) {
                    if (item == ordinal) {
                        return info;
                    }
                    item++;
                }
            }
            recordStart = end + 1;
        }
        return "";
    }

    /**
     * Finds a named field argument and returns its value range as {@code start:end}.  A duplicate
     * name deliberately returns an empty value, so generated bindings cannot accidentally choose
     * one duplicate value over another.
     */
    public static String fieldArgumentValueRange(
            String source,
            String ast,
            int fieldStart,
            String expectedName
    ) {
        if (source == null || expectedName == null || expectedName.length() == 0) {
            return "";
        }
        int count = fieldArgumentCount(ast, fieldStart);
        if (count < 0) {
            return "";
        }
        String found = "";
        int ordinal = 0;
        while (ordinal < count) {
            String info = fieldArgumentInfo(ast, fieldStart, ordinal);
            int nameStart = infoValue(info, 0);
            int nameEnd = infoValue(info, 1);
            int valueStart = infoValue(info, 2);
            int valueEnd = infoValue(info, 3);
            if (nameStart < 0 || nameEnd <= nameStart || valueStart < 0 || valueEnd <= valueStart
                    || nameEnd > source.length() || valueEnd > source.length()) {
                return "";
            }
            if (source.substring(nameStart, nameEnd).equals(expectedName)) {
                if (found.length() != 0) {
                    return "";
                }
                found = valueStart + ":" + valueEnd;
            }
            ordinal++;
        }
        return found;
    }

    /**
     * Finds one selected-operation variable declaration as
     * {@code definitionStart:typeStart:typeEnd:defaultStart|n:defaultEnd|n}.  The name itself is
     * intentionally compared against source here: it prevents a new object model merely to hold
     * a short-lived identifier while retaining the parsed type/default ranges in the AST.
     */
    public static String variableDefinitionInfo(String source, String ast, String expectedName) {
        if (source == null || expectedName == null || expectedName.length() == 0
                || ast == null || ast.indexOf(PREFIX) != 0) {
            return "";
        }
        String found = "";
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nv")) {
                String info = variableInfo(source, ast, recordStart, end);
                int definitionStart = infoValue(info, 0);
                int nameEnd = variableNameEnd(ast, recordStart, end);
                if (definitionStart < 0 || nameEnd <= definitionStart + 1 || nameEnd > source.length()) {
                    return "";
                }
                if (source.substring(definitionStart + 1, nameEnd).equals(expectedName)) {
                    if (found.length() != 0) {
                        return "";
                    }
                    found = info;
                }
            }
            recordStart = end + 1;
        }
        return found;
    }

    /**
     * Reads the two schema-neutral semantics of one already-parsed variable default value as
     * {@code isNull:variableReferenceStart|n:variableReferenceEnd|n}. The enclosing AST has
     * already retained and syntax-validated the value range; this bounded scan therefore only
     * distinguishes the {@code null} literal and a variable reference without consulting the
     * legacy lexical-plan carrier. Dollar signs in normal and block strings (including escaped
     * block-string triple quotes) are deliberately ignored.
     */
    public static String defaultValueInfo(String source, int valueStart, int valueEnd) {
        if (source == null || valueStart < 0 || valueEnd <= valueStart || valueEnd > source.length()) {
            return "";
        }
        boolean nullLiteral = source.substring(valueStart, valueEnd).equals("null");
        int referenceStart = -1;
        int referenceEnd = -1;
        boolean comment = false;
        int position = valueStart;
        while (position < valueEnd) {
            char current = source.charAt(position);
            if (comment) {
                if (current == '\n' || current == '\r') {
                    comment = false;
                }
                position++;
            } else if (current == '#') {
                comment = true;
                position++;
            } else if (current == '"') {
                boolean block = position + 2 < valueEnd && source.charAt(position + 1) == '"'
                        && source.charAt(position + 2) == '"';
                position += block ? 3 : 1;
                boolean closed = false;
                while (position < valueEnd) {
                    current = source.charAt(position);
                    if (block) {
                        if (current == '\\' && position + 3 < valueEnd && source.charAt(position + 1) == '"'
                                && source.charAt(position + 2) == '"' && source.charAt(position + 3) == '"') {
                            position += 4;
                        } else if (current == '"' && position + 2 < valueEnd && source.charAt(position + 1) == '"'
                                && source.charAt(position + 2) == '"') {
                            position += 3;
                            closed = true;
                            break;
                        } else {
                            position++;
                        }
                    } else if (current == '\\') {
                        position += 2;
                    } else if (current == '"') {
                        position++;
                        closed = true;
                        break;
                    } else {
                        position++;
                    }
                }
                if (!closed) {
                    return "";
                }
            } else if (current == '$') {
                int nameStart = position + 1;
                if (nameStart >= valueEnd || !((source.charAt(nameStart) >= 'A' && source.charAt(nameStart) <= 'Z')
                        || (source.charAt(nameStart) >= 'a' && source.charAt(nameStart) <= 'z')
                        || source.charAt(nameStart) == '_')) {
                    return "";
                }
                int nameEnd = nameStart + 1;
                while (nameEnd < valueEnd) {
                    char nameCharacter = source.charAt(nameEnd);
                    if (!((nameCharacter >= 'A' && nameCharacter <= 'Z')
                            || (nameCharacter >= 'a' && nameCharacter <= 'z')
                            || (nameCharacter >= '0' && nameCharacter <= '9') || nameCharacter == '_')) {
                        break;
                    }
                    nameEnd++;
                }
                referenceStart = position;
                referenceEnd = nameEnd;
                break;
            } else {
                position++;
            }
        }
        return (nullLiteral ? "1" : "0") + ":" + (referenceStart < 0 ? "n" : "" + referenceStart)
                + ":" + (referenceEnd < 0 ? "n" : "" + referenceEnd);
    }

    /**
     * Compares two syntax-validated source ranges after removing GraphQL ignored text. String
     * and block-string contents remain literal, so the merge planner never mistakes whitespace
     * or a comment marker inside a value for syntax trivia. This is the AST-path replacement for
     * the legacy token-plan range comparator.
     */
    public static boolean tokenRangesEqual(
            String source,
            int firstStart,
            int firstEnd,
            int secondStart,
            int secondEnd
    ) {
        if (source == null || firstStart < 0 || firstEnd < firstStart || secondStart < 0 || secondEnd < secondStart
                || firstEnd > source.length() || secondEnd > source.length()) {
            return false;
        }
        int first = firstStart;
        int second = secondStart;
        boolean firstString = false;
        boolean secondString = false;
        boolean firstBlock = false;
        boolean secondBlock = false;
        boolean firstEscaped = false;
        boolean secondEscaped = false;
        boolean firstComment = false;
        boolean secondComment = false;
        while (true) {
            while (first < firstEnd && !firstString) {
                char firstCharacter = source.charAt(first);
                if (firstComment) {
                    firstComment = firstCharacter != '\n' && firstCharacter != '\r';
                    first++;
                } else if (firstCharacter == '#') {
                    firstComment = true;
                    first++;
                } else if (firstCharacter == ' ' || firstCharacter == '\t' || firstCharacter == '\n'
                        || firstCharacter == '\r' || firstCharacter == ',') {
                    first++;
                } else {
                    break;
                }
            }
            while (second < secondEnd && !secondString) {
                char secondCharacter = source.charAt(second);
                if (secondComment) {
                    secondComment = secondCharacter != '\n' && secondCharacter != '\r';
                    second++;
                } else if (secondCharacter == '#') {
                    secondComment = true;
                    second++;
                } else if (secondCharacter == ' ' || secondCharacter == '\t' || secondCharacter == '\n'
                        || secondCharacter == '\r' || secondCharacter == ',') {
                    second++;
                } else {
                    break;
                }
            }
            if (first == firstEnd || second == secondEnd) {
                return first == firstEnd && second == secondEnd && !firstString && !secondString;
            }
            char firstCharacter = source.charAt(first);
            char secondCharacter = source.charAt(second);
            if (firstCharacter != secondCharacter || firstString != secondString || firstBlock != secondBlock) {
                return false;
            }
            if (firstString && firstBlock && firstCharacter == '\\' && first + 3 < firstEnd && second + 3 < secondEnd
                    && source.charAt(first + 1) == '"' && source.charAt(first + 2) == '"'
                    && source.charAt(first + 3) == '"' && source.charAt(second + 1) == '"'
                    && source.charAt(second + 2) == '"' && source.charAt(second + 3) == '"') {
                first += 4;
                second += 4;
            } else if (firstString && firstBlock && firstCharacter == '"' && first + 2 < firstEnd
                    && second + 2 < secondEnd && source.charAt(first + 1) == '"'
                    && source.charAt(first + 2) == '"' && source.charAt(second + 1) == '"'
                    && source.charAt(second + 2) == '"') {
                first += 3;
                second += 3;
                firstString = false;
                secondString = false;
                firstBlock = false;
                secondBlock = false;
            } else if (firstString && !firstBlock && firstEscaped) {
                first++;
                second++;
                firstEscaped = false;
                secondEscaped = false;
            } else if (firstString && !firstBlock && firstCharacter == '\\') {
                first++;
                second++;
                firstEscaped = true;
                secondEscaped = true;
            } else {
                if (firstString && !firstBlock && firstCharacter == '"') {
                    firstString = false;
                    secondString = false;
                } else if (!firstString && firstCharacter == '"') {
                    firstString = true;
                    secondString = true;
                    firstBlock = first + 2 < firstEnd && source.charAt(first + 1) == '"'
                            && source.charAt(first + 2) == '"';
                    secondBlock = second + 2 < secondEnd && source.charAt(second + 1) == '"'
                            && source.charAt(second + 2) == '"';
                    if (firstBlock != secondBlock) {
                        return false;
                    }
                }
                first++;
                second++;
            }
        }
    }

    /**
     * Returns the next directive immediately following a parsed header as
     * {@code start:end:nameStart:nameEnd}.  Only GraphQL ignored text may lie between
     * {@code position} and the directive.  Consequently a directive belonging to a later field,
     * spread, or definition is never borrowed by the current AST node.
     */
    public static String directiveInfoAtOrAfter(String source, String ast, int position) {
        if (source == null || ast == null || ast.indexOf(PREFIX) != 0 || position < 0
                || position > source.length()) {
            return "";
        }
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nd")) {
                String info = directiveInfo(ast, recordStart, end);
                int directiveStart = infoValue(info, 0);
                if (directiveStart < 0) {
                    return "";
                }
                if (directiveStart >= position) {
                    return onlyIgnored(source, position, directiveStart) ? info : "";
                }
            }
            recordStart = end + 1;
        }
        return "";
    }

    /** Returns whether a retained directive belongs to the selected operation header. */
    public static boolean hasDirectiveBeforeSelection(String ast, int selectionStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || selectionStart < 0) {
            return false;
        }
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return false;
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nd")) {
                int directiveStart = infoValue(directiveInfo(ast, recordStart, end), 0);
                if (directiveStart < 0) {
                    return false;
                }
                if (directiveStart < selectionStart) {
                    return true;
                }
            }
            recordStart = end + 1;
        }
        return false;
    }

    /**
     * Returns the first directive retained on the selected operation header, or {@code -1} when
     * the operation has none or the carrier is malformed. Directives before the operation root
     * selection can only belong to the operation header; field and fragment directives occur
     * inside or after that selection. Keeping this boundary in the AST lets execution avoid a
     * second lexical walk once the request-local carrier has been built.
     */
    public static int operationDirectiveStart(String ast) {
        int selectionStart = operationSelectionStart(ast);
        if (selectionStart < 0) {
            return -1;
        }
        int first = -1;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nd")) {
                int directiveStart = infoValue(directiveInfo(ast, recordStart, end), 0);
                if (directiveStart < 0) {
                    return -1;
                }
                if (directiveStart < selectionStart && (first < 0 || directiveStart < first)) {
                    first = directiveStart;
                }
            }
            recordStart = end + 1;
        }
        return first;
    }

    /**
     * Returns the first directive attached to a retained named-fragment definition, or
     * {@code -1} when every definition is directive-free. The syntax carrier records the first
     * token after the type condition and the opening selection token; a smaller first-token
     * offset therefore identifies a definition directive without rescanning GraphQL source.
     */
    public static int fragmentDefinitionDirectiveStart(String ast) {
        if (!hasPrefix(ast)) {
            return -1;
        }
        int firstDirective = -1;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nr")) {
                int first = ast.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
                int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
                int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
                int eighth = seventh < 0 ? -1 : ast.indexOf(':', seventh + 1);
                if (first != recordStart + 2 || second < 0 || third != second + 2
                        || !ast.substring(second + 1, third).equals("n") || fourth < 0 || fifth < 0
                        || sixth < 0 || seventh < 0 || eighth < 0 || eighth >= end) {
                    return -1;
                }
                int directivesStart = decimal(ast, sixth + 1, seventh);
                int selectionStart = decimal(ast, seventh + 1, eighth);
                if (directivesStart < 0 || selectionStart < directivesStart) {
                    return -1;
                }
                if (directivesStart < selectionStart
                        && (firstDirective < 0 || directivesStart < firstDirective)) {
                    firstDirective = directivesStart;
                }
            }
            recordStart = end + 1;
        }
        return firstDirective;
    }

    /** Returns one typed node kind, or an empty value when the carrier or ordinal is invalid. */
    public static String nodeKind(String ast, int ordinal) {
        int start = nodeStartOffset(ast, ordinal);
        int end = start < 0 ? -1 : ast.indexOf(':', start + 1);
        return end != start + 2 ? "" : ast.substring(start + 1, end);
    }

    /** Returns one node's source offset, or {@code -1} when the carrier or ordinal is invalid. */
    public static int nodeSourceStart(String ast, int ordinal) {
        int start = nodeStartOffset(ast, ordinal);
        int first = start < 0 ? -1 : ast.indexOf(':', start + 1);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        return second < 0 ? -1 : decimal(ast, first + 1, second);
    }

    /** Returns a node's direct selection parent, or {@code -1} when it has no such parent. */
    public static int nodeParentSelection(String ast, int ordinal) {
        int start = nodeStartOffset(ast, ordinal);
        int first = start < 0 ? -1 : ast.indexOf(':', start + 1);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        if (third < 0 || second + 1 == third && ast.charAt(second + 1) != 'n') {
            return -1;
        }
        if (third == second + 2 && ast.charAt(second + 1) == 'n') {
            return -1;
        }
        return decimal(ast, second + 1, third);
    }

    /** Returns the node payload after its direct-parent slot, or empty for an invalid carrier. */
    public static String nodePayload(String ast, int ordinal) {
        int start = nodeStartOffset(ast, ordinal);
        int first = start < 0 ? -1 : ast.indexOf(':', start + 1);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int end = third < 0 ? -1 : ast.indexOf(';', third + 1);
        return end <= third ? "" : ast.substring(third + 1, end);
    }

    /**
     * Returns the direct selection set containing a field node, or {@code -1} for an invalid
     * carrier or unknown field location. This is the typed replacement for reverse-scanning
     * scalar selection-topology records at relation-planning time.
     */
    public static int fieldParentSelection(String ast, int fieldStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || fieldStart < 0) {
            return -1;
        }
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nf")) {
                int first = ast.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                if (first != recordStart + 2 || second < 0 || third < 0 || third >= end) {
                    return -1;
                }
                int sourceStart = decimal(ast, first + 1, second);
                int parentSelection = decimal(ast, second + 1, third);
                if (sourceStart < 0 || parentSelection < 0) {
                    return -1;
                }
                if (sourceStart == fieldStart) {
                    return parentSelection;
                }
            }
            recordStart = end + 1;
        }
        return -1;
    }

    /**
     * Returns the field whose child selection is {@code selectionStart}, or {@code -1} when no
     * typed field owns that selection. A field without a child selection is never an owner.
     */
    public static int selectionOwnerFieldStart(String ast, int selectionStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || selectionStart < 0) {
            return -1;
        }
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nf")) {
                int first = ast.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
                if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                        || fifth >= end) {
                    return -1;
                }
                int sourceStart = decimal(ast, first + 1, second);
                String child = ast.substring(fourth + 1, fifth);
                int childSelection = child.equals("n") ? -1 : decimal(child, 0, child.length());
                if (sourceStart < 0) {
                    return -1;
                }
                if (childSelection == selectionStart) {
                    return sourceStart;
                }
            }
            recordStart = end + 1;
        }
        return -1;
    }

    /**
     * Returns the name of the named-fragment definition whose selection starts at
     * {@code selectionStart}. An empty result means that the selection is not exactly one retained
     * named-fragment body or that the AST is malformed.
     */
    public static String fragmentDefinitionNameForSelection(String source, String ast, int selectionStart) {
        if (source == null || ast == null || ast.indexOf(PREFIX) != 0 || selectionStart < 0) {
            return "";
        }
        String found = "";
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nr")) {
                int first = ast.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
                int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
                int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
                int eighth = seventh < 0 ? -1 : ast.indexOf(':', seventh + 1);
                if (first != recordStart + 2 || second < 0 || third != second + 2
                        || !ast.substring(second + 1, third).equals("n") || fourth < 0 || fifth < 0
                        || sixth < 0 || seventh < 0 || eighth < 0 || eighth >= end) {
                    return "";
                }
                int nameStart = decimal(ast, first + 1, second);
                int nameEnd = decimal(ast, third + 1, fourth);
                int fragmentSelection = decimal(ast, seventh + 1, eighth);
                if (nameStart < 0 || nameEnd <= nameStart || nameEnd > source.length()
                        || fragmentSelection < 0 || fragmentSelection > source.length()) {
                    return "";
                }
                if (fragmentSelection == selectionStart) {
                    if (found.length() != 0) {
                        return "";
                    }
                    found = source.substring(nameStart, nameEnd);
                }
            }
            recordStart = end + 1;
        }
        return found;
    }

    /**
     * Returns one direct parent selection that spreads {@code fragmentName}, in source order.
     * A caller can advance {@code occurrence} to inspect all retained uses without reparsing the
     * request text.
     */
    public static int namedFragmentSpreadParentSelection(
            String source,
            String ast,
            String fragmentName,
            int occurrence
    ) {
        if (source == null || ast == null || ast.indexOf(PREFIX) != 0 || fragmentName == null
                || fragmentName.length() == 0 || occurrence < 0) {
            return -1;
        }
        int matched = 0;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Np")) {
                int first = ast.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
                if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                        || fifth >= end) {
                    return -1;
                }
                int parentSelection = decimal(ast, second + 1, third);
                int nameStart = decimal(ast, third + 1, fourth);
                int nameEnd = decimal(ast, fourth + 1, fifth);
                int directivesStart = decimal(ast, fifth + 1, end);
                if (parentSelection < 0 || nameStart < 0 || nameEnd <= nameStart || nameEnd > source.length()
                        || directivesStart < nameEnd || directivesStart > source.length()) {
                    return -1;
                }
                if (source.substring(nameStart, nameEnd).equals(fragmentName)) {
                    if (matched == occurrence) {
                        return parentSelection;
                    }
                    matched++;
                    if (matched > MAX_NODES) {
                        return -1;
                    }
                }
            }
            recordStart = end + 1;
        }
        return -1;
    }

    /** Returns the parent selection that contains an inline fragment body, if any. */
    public static int inlineFragmentParentSelection(String ast, int selectionStart) {
        if (ast == null || ast.indexOf(PREFIX) != 0 || selectionStart < 0) {
            return -1;
        }
        int found = -1;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Ni")) {
                int first = ast.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
                int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
                int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
                if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                        || sixth < 0 || seventh < 0 || seventh >= end) {
                    return -1;
                }
                int parentSelection = decimal(ast, second + 1, third);
                int childSelection = decimal(ast, sixth + 1, seventh);
                if (parentSelection < 0 || childSelection < 0) {
                    return -1;
                }
                if (childSelection == selectionStart) {
                    if (found >= 0) {
                        return -1;
                    }
                    found = parentSelection;
                }
            }
            recordStart = end + 1;
        }
        return found;
    }

    /**
     * Returns a retained named fragment header as
     * {@code typeStart:typeEnd:directivesStart:selectionStart:selectionEnd}. The AST has already
     * established each fragment node's source topology, so this avoids returning to the scalar
     * syntax index during fragment expansion. An absent, malformed, or duplicate definition
     * returns an empty value.
     */
    public static String fragmentDefinitionInfo(String source, String ast, String expectedName) {
        if (source == null || expectedName == null || expectedName.length() == 0
                || ast == null || ast.indexOf(PREFIX) != 0) {
            return "";
        }
        String found = "";
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            if (end > recordStart + 2 && ast.substring(recordStart, recordStart + 2).equals("Nr")) {
                int first = ast.indexOf(':', recordStart + 2);
                int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
                int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
                int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
                int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
                int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
                int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
                int eighth = seventh < 0 ? -1 : ast.indexOf(':', seventh + 1);
                if (first != recordStart + 2 || second < 0 || third != second + 2
                        || ast.substring(second + 1, third).equals("n") == false || fourth < 0 || fifth < 0
                        || sixth < 0 || seventh < 0 || eighth < 0 || eighth >= end) {
                    return "";
                }
                int nameStart = decimal(ast, first + 1, second);
                int nameEnd = decimal(ast, third + 1, fourth);
                int typeStart = decimal(ast, fourth + 1, fifth);
                int typeEnd = decimal(ast, fifth + 1, sixth);
                int directivesStart = decimal(ast, sixth + 1, seventh);
                int selectionStart = decimal(ast, seventh + 1, eighth);
                int selectionEnd = decimal(ast, eighth + 1, end);
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
            recordStart = end + 1;
        }
        return found;
    }

    /** Counts retained named- and inline-fragment type conditions. */
    public static int typeConditionCount(String ast) {
        if (!hasPrefix(ast)) {
            return -1;
        }
        int count = 0;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return -1;
            }
            String info = typeConditionRecordInfo(ast, recordStart, end);
            if (info.equals("invalid")) {
                return -1;
            }
            if (info.length() != 0) {
                count++;
            }
            recordStart = end + 1;
        }
        return count;
    }

    /** Returns {@code nodeStart:typeStart:typeEnd} for one retained fragment type condition. */
    public static String typeConditionInfo(String ast, int ordinal) {
        if (!hasPrefix(ast) || ordinal < 0) {
            return "";
        }
        int found = 0;
        int recordStart = PREFIX.length();
        while (recordStart < ast.length()) {
            int end = ast.indexOf(';', recordStart);
            if (end <= recordStart || !ast.substring(recordStart, recordStart + 1).equals("N")) {
                return "";
            }
            String info = typeConditionRecordInfo(ast, recordStart, end);
            if (info.equals("invalid")) {
                return "";
            }
            if (info.length() != 0) {
                if (found == ordinal) return info;
                found++;
            }
            recordStart = end + 1;
        }
        return "";
    }

    private static String typeConditionRecordInfo(String ast, int recordStart, int end) {
        if (end <= recordStart + 2) return "";
        String kind = ast.substring(recordStart + 1, recordStart + 2);
        if (!kind.equals("r") && !kind.equals("i")) return "";
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
        int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
        int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
        int nodeStart = first < 0 || second < 0 ? -1 : decimal(ast, first + 1, second);
        int typeStart;
        int typeEnd;
        if (kind.equals("r")) {
            int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
            int eighth = seventh < 0 ? -1 : ast.indexOf(':', seventh + 1);
            if (first != recordStart + 2 || second < 0 || third != second + 2
                    || !ast.substring(second + 1, third).equals("n") || fourth < 0 || fifth < 0
                    || sixth < 0 || seventh < 0 || eighth < 0 || eighth >= end) {
                return "invalid";
            }
            typeStart = decimal(ast, fourth + 1, fifth);
            typeEnd = decimal(ast, fifth + 1, sixth);
        } else {
            int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
            if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                    || sixth < 0 || seventh < 0 || seventh >= end) {
                return "invalid";
            }
            typeStart = decimal(ast, third + 1, fourth);
            typeEnd = decimal(ast, fourth + 1, fifth);
            if (typeStart < 0 && ast.substring(third + 1, fourth).equals("n")
                    && typeEnd < 0 && ast.substring(fourth + 1, fifth).equals("n")) {
                return "";
            }
        }
        if (nodeStart < 0 || typeStart < 0 || typeEnd <= typeStart) {
            return "invalid";
        }
        return nodeStart + ":" + typeStart + ":" + typeEnd;
    }

    private static boolean hasPrefix(String value) {
        return value != null && value.length() >= PREFIX.length()
                && value.substring(0, PREFIX.length()).equals(PREFIX);
    }

    private static int nodeStartOffset(String ast, int ordinal) {
        if (ordinal < 0 || !hasPrefix(ast)) {
            return -1;
        }
        int position = PREFIX.length();
        int index = 0;
        while (position < ast.length()) {
            int end = ast.indexOf(';', position);
            if (end <= position || !ast.substring(position, position + 1).equals("N")
                    || nodeFirstColon(ast, position, end) < 0) {
                return -1;
            }
            if (index == ordinal) {
                return position;
            }
            index++;
            position = end + 1;
        }
        return -1;
    }

    private static int nodeFirstColon(String ast, int start, int end) {
        int colon = ast.indexOf(':', start + 1);
        return colon > start + 1 && colon < end ? colon : -1;
    }

    /** Parses one {@code Na} record's direct parent (the field or directive source offset). */
    private static int argumentOwner(String ast, int recordStart, int end) {
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        if (first != recordStart + 2 || second < 0 || third < 0 || third >= end) {
            return -1;
        }
        return decimal(ast, second + 1, third);
    }

    /** Parses one {@code Na} record as {@code nameStart:nameEnd:valueStart:valueEnd}. */
    private static String argumentInfo(String ast, int recordStart, int end) {
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
        int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
        int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
        if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                || sixth < 0 || sixth >= end) {
            return "";
        }
        int valueStart = decimal(ast, first + 1, second);
        int owner = decimal(ast, second + 1, third);
        int nameStart = decimal(ast, third + 1, fourth);
        int nameEnd = decimal(ast, fourth + 1, fifth);
        int retainedValueStart = decimal(ast, fifth + 1, sixth);
        int valueEnd = decimal(ast, sixth + 1, end);
        if (valueStart < 0 || owner < 0 || nameStart < 0 || nameEnd <= nameStart
                || retainedValueStart != valueStart || valueEnd <= valueStart) {
            return "";
        }
        return nameStart + ":" + nameEnd + ":" + valueStart + ":" + valueEnd;
    }

    /** Returns the variable-name end range from a {@code Nv} record. */
    private static int variableNameEnd(String ast, int recordStart, int end) {
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
        if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fourth >= end
                || !ast.substring(second + 1, third).equals("n")) {
            return -1;
        }
        return decimal(ast, third + 1, fourth);
    }

    /** Parses one {@code Nv} record into the compact variable-definition range carrier. */
    private static String variableInfo(String source, String ast, int recordStart, int end) {
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
        int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
        int sixth = fifth < 0 ? -1 : ast.indexOf(':', fifth + 1);
        int seventh = sixth < 0 ? -1 : ast.indexOf(':', sixth + 1);
        if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                || sixth < 0 || seventh < 0 || seventh >= end
                || !ast.substring(second + 1, third).equals("n")) {
            return "";
        }
        int definitionStart = decimal(ast, first + 1, second);
        int nameEnd = decimal(ast, third + 1, fourth);
        int typeStart = decimal(ast, fourth + 1, fifth);
        int typeEnd = decimal(ast, fifth + 1, sixth);
        String defaultStart = ast.substring(sixth + 1, seventh);
        String defaultEnd = ast.substring(seventh + 1, end);
        int parsedDefaultStart = defaultStart.equals("n") ? -1 : decimal(defaultStart, 0, defaultStart.length());
        int parsedDefaultEnd = defaultEnd.equals("n") ? -1 : decimal(defaultEnd, 0, defaultEnd.length());
        if (definitionStart < 0 || nameEnd <= definitionStart + 1 || typeStart < nameEnd
                || typeEnd <= typeStart || typeEnd > source.length()
                || (parsedDefaultStart < 0 && !defaultStart.equals("n"))
                || (parsedDefaultEnd < 0 && !defaultEnd.equals("n"))
                || ((parsedDefaultStart < 0) != (parsedDefaultEnd < 0))
                || (parsedDefaultStart >= 0 && (parsedDefaultEnd <= parsedDefaultStart
                || parsedDefaultEnd > source.length()))) {
            return "";
        }
        return definitionStart + ":" + typeStart + ":" + typeEnd + ":"
                + defaultStart + ":" + defaultEnd;
    }

    /** Parses one {@code Nd} record as {@code start:end:nameStart:nameEnd}. */
    private static String directiveInfo(String ast, int recordStart, int end) {
        int first = ast.indexOf(':', recordStart + 2);
        int second = first < 0 ? -1 : ast.indexOf(':', first + 1);
        int third = second < 0 ? -1 : ast.indexOf(':', second + 1);
        int fourth = third < 0 ? -1 : ast.indexOf(':', third + 1);
        int fifth = fourth < 0 ? -1 : ast.indexOf(':', fourth + 1);
        if (first != recordStart + 2 || second < 0 || third < 0 || fourth < 0 || fifth < 0
                || fifth >= end || !ast.substring(second + 1, third).equals("n")) {
            return "";
        }
        int start = decimal(ast, first + 1, second);
        int directiveEnd = decimal(ast, third + 1, fourth);
        int nameStart = decimal(ast, fourth + 1, fifth);
        int nameEnd = decimal(ast, fifth + 1, end);
        if (start < 0 || directiveEnd <= start || nameStart <= start || nameEnd <= nameStart
                || nameEnd > directiveEnd) {
            return "";
        }
        return start + ":" + directiveEnd + ":" + nameStart + ":" + nameEnd;
    }

    /** Checks GraphQL ignored text without creating another lexical/token carrier. */
    private static boolean onlyIgnored(String source, int start, int end) {
        if (start < 0 || end < start || end > source.length()) {
            return false;
        }
        boolean inComment = false;
        int position = start;
        while (position < end) {
            char current = source.charAt(position);
            if (inComment) {
                if (current == '\n' || current == '\r') {
                    inComment = false;
                }
            } else if (current == '#') {
                inComment = true;
            } else if (current != ' ' && current != '\t' && current != '\n' && current != '\r'
                    && current != ',') {
                return false;
            }
            position++;
        }
        return true;
    }

    /** Reads a numeric or {@code n} slot from a compact range carrier. */
    private static int infoValue(String info, int slot) {
        if (info == null || slot < 0) {
            return -1;
        }
        int start = 0;
        int index = 0;
        while (start <= info.length()) {
            int end = info.indexOf(':', start);
            if (end < 0) {
                end = info.length();
            }
            if (index == slot) {
                String value = info.substring(start, end);
                return value.equals("n") ? -1 : decimal(value, 0, value.length());
            }
            if (end == info.length()) {
                return -1;
            }
            start = end + 1;
            index++;
        }
        return -1;
    }

    private static int decimal(String value, int start, int end) {
        if (value == null || start < 0 || end <= start || end > value.length()) {
            return -1;
        }
        int result = 0;
        while (start < end) {
            char current = value.charAt(start);
            if (current < '0' || current > '9' || result > 214748364) {
                return -1;
            }
            int digit = current - '0';
            if (result == 214748364 && digit > 7) {
                return -1;
            }
            result = result * 10 + digit;
            start++;
        }
        return result;
    }
}
