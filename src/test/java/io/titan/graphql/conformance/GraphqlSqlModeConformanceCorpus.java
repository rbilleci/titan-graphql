package io.titan.graphql.conformance;

import io.titan.graphql.TitanGraphqlFunctions;
import io.titan.graphql.sqlmode.GraphqlSqlEntryPointDispatch;
import io.titan.graphql.sqlmode.GraphqlSqlEntryPointDispatch.Invocation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The SQL-mode conformance corpus (completion plan W2).
 *
 * <p>Every case is one invocation of a public generated entry point
 * ({@code DemoBlogTitanGraphqlFunctions} {@code @StoredFunction}s), tagged with the
 * {@code docs/query-contract-conformance.md} row it evidences. The same case executes on both
 * legs:</p>
 *
 * <ul>
 *   <li><b>Java mode</b>: the kernel's static method, in-JVM, via
 *       {@link TitanGraphqlFunctions} — exactly how the 450-test Java-mode suite invokes
 *       it;</li>
 *   <li><b>SQL mode</b>: {@code SELECT public.&lt;function&gt;(...)} against the transpiled
 *       routines deployed from the {@code titanPackage} migrations onto a live
 *       PostgreSQL.</li>
 * </ul>
 *
 * <p>The queries are drawn from the Java-mode conformance tests
 * ({@code TitanGraphqlFunctionsTest}) so the corpus exercises the same behaviors the matrix
 * already cites for Java mode. {@code GraphqlSqlModeConformanceCoverageTest} (Docker-free)
 * guards that every ACCEPTED matrix row is covered here; {@code GraphqlSqlModeEquivalenceIT}
 * (Docker) executes the corpus and compares both legs as canonical JSON through core's
 * {@code EquivalenceOracle}.</p>
 */
public final class GraphqlSqlModeConformanceCorpus {

    private GraphqlSqlModeConformanceCorpus() {
    }

    /**
     * ACCEPTED matrix rows that intentionally carry no corpus case.
     *
     * <p>{@code QC10-MATRIX} is the conformance document itself — a documentation row with no
     * executable behavior to compare; it stays evidenced by
     * {@code GraphqlConformanceMatrixTest}.</p>
     */
    public static final Set<String> ROWS_WITHOUT_EXECUTABLE_BEHAVIOR = Set.of("QC10-MATRIX");

    /**
     * One corpus case: the matrix row it evidences, a stable name, and the invocation.
     *
     * <p>The invocation model and SQL dispatch were extracted to main code
     * ({@link GraphqlSqlEntryPointDispatch}) for the W5.1 live execution mode; the corpus
     * reuses them so both consumers share one set of entry-point shapes.</p>
     */
    public record Case(String rowId, String name, Invocation invocation) {
    }

    /** Java-mode leg: dispatch to the kernel's static entry point in this JVM. */
    public static String executeJavaMode(Invocation invocation) {
        return switch (invocation) {
            case Invocation.Execute c -> TitanGraphqlFunctions.executeGraphql(c.query(), c.actorId(), c.actorRole());
            case Invocation.Request c ->
                    TitanGraphqlFunctions.executeGraphqlRequest(c.query(), c.operationName(), c.actorId(), c.actorRole());
            case Invocation.RequestWithVariables c -> TitanGraphqlFunctions.executeGraphqlRequestWithVariables(
                    c.query(), c.operationName(), c.variablesJson(), c.extensionsJson(), c.actorId(), c.actorRole());
            case Invocation.WithContext c -> TitanGraphqlFunctions.executeGraphqlWithContext(
                    c.query(), c.actorId(), c.actorRole(),
                    c.enablePublishedVisibility(), c.hasArticleVisibility(), c.articleVisibility());
            case Invocation.WithIntrospection c -> TitanGraphqlFunctions.executeGraphqlWithIntrospection(
                    c.query(), c.actorId(), c.actorRole(), c.enableIntrospection());
            case Invocation.CompactContext c -> TitanGraphqlFunctions.executeGraphqlRequestWithCompactContext(
                    c.query(), c.operationName(), c.variablesJson(), c.extensionsJson(), c.actorId(), c.actorRole(),
                    c.enablePublishedVisibility(), c.hasArticleVisibility(), c.articleVisibility(),
                    c.enableIntrospection(), c.tenantId(), c.requestId(), c.policyFlags(),
                    c.enabledContextFilters(), c.deadlineBudgetMillis());
        };
    }

    /**
     * SQL-mode leg: {@code SELECT public.<function>(...)} against the deployed routines —
     * the extracted main-code dispatch the live execution mode serves with.
     */
    public static String executeSqlMode(Connection connection, Invocation invocation) throws SQLException {
        return GraphqlSqlEntryPointDispatch.execute(connection, invocation);
    }

    public static List<Case> cases() {
        List<Case> cases = new ArrayList<>();

        // --- QC1: operations ---------------------------------------------------------------
        cases.add(execute("QC1-OPERATIONS-QUERY", "shorthand-article-author",
                "{ article(id: 1) { id title author { id name } } }"));
        cases.add(execute("QC1-OPERATIONS-QUERY", "explicit-query-article",
                "query { article(id: 1) { id title } }"));
        cases.add(execute("QC1-OPERATIONS-NAMED", "named-single-query",
                "query FetchArticle { article(id:1){id} }"));
        cases.add(new Case("QC1-OPERATIONS-SELECTED", "select-second-operation",
                new Invocation.Request(
                        "query First { article(id:1){id title} }\nquery Second { article(id:2){id title} }\n",
                        "Second", 10L, "reader")));
        cases.add(new Case("QC1-OPERATIONS-SELECTED", "select-operation-with-fragments",
                new Invocation.Request(
                        "query First { article(id:1){id} }\n"
                                + "query Second { article(id:2){...ArticleFields} }\n"
                                + "fragment ArticleFields on Article { id title }\n",
                        "Second", 10L, "reader")));
        cases.add(new Case("QC1-OPERATIONS-SELECTED", "unknown-operation-name",
                new Invocation.Request("query First { article(id:1){id} }", "Second", 10L, "reader")));
        cases.add(new Case("QC1-OPERATIONS-SELECTED", "ambiguous-operation-name",
                new Invocation.Request(
                        "query First { article(id:1){id} } query First { article(id:2){id} }",
                        "First", 10L, "reader")));
        cases.add(execute("QC1-OPERATIONS-MULTI-MISSING", "multi-operation-without-name",
                "query First { article(id:1){id} } query Second { article(id:2){id} }"));
        cases.add(execute("QC1-OPERATIONS-MUTATION", "mutation-rejected",
                "mutation Change { article(id: 1) { id } }"));
        cases.add(new Case("QC1-OPERATIONS-MUTATION", "selected-mutation-rejected",
                new Invocation.Request(
                        "query First { article(id:1){id} } mutation Change { article(id:2){id} }",
                        "Change", 10L, "reader")));
        cases.add(execute("QC1-OPERATIONS-SUBSCRIPTION", "subscription-rejected",
                "subscription Watch { article(id: 1) { id } }"));

        // --- QC1: request envelope ---------------------------------------------------------
        cases.add(new Case("QC1-REQUEST-ENVELOPE", "envelope-variables-and-extensions",
                new Invocation.RequestWithVariables(
                        "query Pick($id: ID!, $showTitle: Boolean!) {\n"
                                + "  article(id: $id) {\n"
                                + "    id\n"
                                + "    title @include(if: $showTitle)\n"
                                + "  }\n"
                                + "}\n",
                        "Pick", "{\"id\":\"1\",\"showTitle\":true}", "{}", 10L, "reader")));
        cases.add(new Case("QC1-REQUEST-ENVELOPE", "envelope-malformed-variables",
                new Invocation.RequestWithVariables(
                        "query Fetch($id: ID!) { article(id: $id) { id } }",
                        "Fetch", "[]", "{}", 10L, "reader")));
        cases.add(new Case("QC1-REQUEST-ENVELOPE", "envelope-malformed-extensions",
                new Invocation.RequestWithVariables(
                        "query Fetch($id: ID!) { article(id: $id) { id } }",
                        "Fetch", "{\"id\":1}", "[]", 10L, "reader")));

        // --- QC2: error shape ----------------------------------------------------------------
        cases.add(execute("QC2-ERROR-SHAPE", "parse-error-envelope",
                "query {\n  article(id: 1) {\n    title @\n  }\n}\n"));
        cases.add(execute("QC2-ERROR-SHAPE", "validation-error-envelope",
                "{ viewer { id } }"));
        cases.add(execute("QC2-ERROR-SHAPE", "authorization-error-envelope",
                "query {\n  article(id: 1) {\n    author {\n      email\n    }\n  }\n}\n"));
        cases.add(execute("QC2-ERROR-SHAPE", "authorized-admin-email",
                "query {\n  article(id: 1) {\n    author {\n      email\n    }\n  }\n}\n", 99L, "admin"));

        // --- QC3: variables ------------------------------------------------------------------
        cases.add(new Case("QC3-VARIABLES-SCALAR", "scalar-variable",
                new Invocation.RequestWithVariables(
                        "query Fetch($first: Int!) { articles(first: $first) { edges { node { id title } } } }",
                        "Fetch", "{\"first\":1}", "{}", 10L, "reader")));
        cases.add(new Case("QC3-VARIABLES-SCALAR", "variable-default",
                new Invocation.RequestWithVariables(
                        "query Fetch($first: Int! = 1) { articles(first: $first) { edges { node { id title } } } }",
                        "Fetch", "{}", "{}", 10L, "reader")));
        cases.add(new Case("QC3-VARIABLES-SCALAR", "unknown-variable",
                new Invocation.RequestWithVariables(
                        "query Fetch($id: ID!) { article(id: $id) { id } }",
                        "Fetch", "{\"id\":1,\"extra\":2}", "{}", 10L, "reader")));
        cases.add(new Case("QC3-VARIABLES-SCALAR", "missing-required-variable",
                new Invocation.RequestWithVariables(
                        "query Fetch($id: ID!) { article(id: $id) { id } }",
                        "Fetch", "{}", "{}", 10L, "reader")));
        cases.add(new Case("QC3-INPUT-OBJECTS", "structured-filter-variable",
                new Invocation.RequestWithVariables(
                        "query Fetch($filter: ArticleFilter!) {\n"
                                + "  articles(filter: $filter, first: 2) {\n"
                                + "    edges { node { id title } }\n"
                                + "    totalCount\n"
                                + "  }\n"
                                + "}\n",
                        "Fetch",
                        "{\"filter\":{\"authorName\":{\"startsWith\":\"Grace\"},\"not\":{\"title\":{\"contains\":\"GraphQL\"}}}}",
                        "{}", 10L, "reader")));
        cases.add(new Case("QC3-INPUT-OBJECTS", "structured-order-variable",
                new Invocation.RequestWithVariables(
                        "query Fetch($orderBy: [ArticleOrderBy!]!) {\n"
                                + "  articles(orderBy: $orderBy, first: 2) {\n"
                                + "    edges { node { id titleLength } }\n"
                                + "  }\n"
                                + "}\n",
                        "Fetch", "{\"orderBy\":[{\"titleLength\":\"DESC\"},{\"id\":\"ASC\"}]}", "{}", 10L, "reader")));
        cases.add(new Case("QC3-INPUT-OBJECTS", "malformed-structured-variable",
                new Invocation.RequestWithVariables(
                        "query Fetch($filter: ArticleFilter!) { articles(filter: $filter, first: 2) { edges { node { id } } } }",
                        "Fetch", "{\"filter\":[]}", "{}", 10L, "reader")));

        // --- QC4: aliases and __typename -------------------------------------------------------
        cases.add(execute("QC4-ALIASES", "root-and-nested-aliases",
                "{post: article(id:1){headline: title writer: author { displayName: name }}}"));
        cases.add(execute("QC4-ALIASES", "connection-aliases",
                "{ feed: articles(first: 1) { rows: edges { mark: cursor item: node { headline: title } } "
                        + "info: pageInfo { more: hasNextPage start: startCursor } } }"));
        cases.add(execute("QC4-ALIASES", "conflicting-alias-response-keys",
                "{ article(id: 1) { title title: id } }"));
        cases.add(execute("QC4-ALIASES", "identical-duplicate-response-keys",
                "{ article(id: 1) { title title } }"));
        cases.add(execute("QC4-TYPENAME", "typename-meta-fields",
                "{article(id:1){__typename kind: __typename title author { __typename } "
                        + "comments(first:1) { edges { node { __typename body } } }}}"));
        cases.add(execute("QC4-TYPENAME", "aliased-typename-only-once",
                "{article(id:1){kind: __typename author { roleType: __typename } "
                        + "comments(first:1) { edges { node { commentType: __typename } } }}}"));

        // --- QC5: fragments and directives ------------------------------------------------------
        cases.add(execute("QC5-FRAGMENTS", "named-fragments",
                "query {\n  article(id:1) {\n    ...ArticleFields\n    author {\n      ...UserFields\n    }\n  }\n}\n"
                        + "fragment ArticleFields on Article {\n  title\n}\n"
                        + "fragment UserFields on User {\n  name\n}\n"));
        cases.add(execute("QC5-FRAGMENTS", "fragments-in-connection-nodes",
                "query {\n  articles(first: 1) {\n    edges {\n      node {\n        ...ArticleFields\n      }\n    }\n  }\n}\n"
                        + "fragment ArticleFields on Article {\n  title\n  comments(first: 1) {\n"
                        + "    edges {\n      node {\n        ...CommentFields\n      }\n    }\n  }\n}\n"
                        + "fragment CommentFields on Comment {\n  body\n}\n"));
        cases.add(execute("QC5-FRAGMENTS", "inline-fragments",
                "query {\n  article(id: 1) {\n    ... on Article {\n      headline: title\n      author {\n"
                        + "        ... on User {\n          displayName: name\n        }\n      }\n"
                        + "      comments(first: 1) {\n        edges {\n          node {\n"
                        + "            ... on Comment {\n              body\n            }\n          }\n        }\n      }\n"
                        + "    }\n  }\n}\n"));
        cases.add(execute("QC5-FRAGMENTS", "non-matching-inline-fragment",
                "query {\n  article(id: 1) {\n    title\n    author {\n      ... on Article {\n"
                        + "        missingArticleField\n      }\n      ... on User {\n        name\n      }\n    }\n  }\n}\n"));
        cases.add(execute("QC5-FRAGMENTS", "fragment-cycle-rejected",
                "{article(id:1){...ArticleFields}}\n"
                        + "fragment ArticleFields on Article {\n  ...MoreArticleFields\n}\n"
                        + "fragment MoreArticleFields on Article {\n  ...ArticleFields\n}\n"));
        cases.add(execute("QC5-FRAGMENTS", "unknown-fragment-rejected",
                "{article(id:1){...ArticleFields}}"));
        cases.add(execute("QC5-FRAGMENTS", "unused-fragment-rejected",
                "{article(id:1){title}}\nfragment ArticleFields on Article {\n  id\n}\n"));
        cases.add(execute("QC5-FRAGMENTS", "unknown-fragment-type-condition",
                "{article(id:1){...ArticleFields}}\nfragment ArticleFields on UnknownType {\n  id\n}\n"));
        cases.add(execute("QC5-DIRECTIVES-LITERAL", "literal-field-directives",
                "query {\n  article(id: 1) {\n    id @include(if: false)\n    kind: __typename @skip(if: false)\n"
                        + "    title @include(if: true)\n    author @skip(if: true) {\n      missingField\n    }\n  }\n}\n"));
        cases.add(execute("QC5-DIRECTIVES-LITERAL", "literal-inline-fragment-directives",
                "query {\n  article(id: 1) {\n    title\n    ... on Article @skip(if: true) {\n      missingField\n    }\n"
                        + "    ... on Article @include(if: true) {\n      author {\n        name\n      }\n    }\n  }\n}\n"));
        cases.add(new Case("QC5-DIRECTIVES-VARIABLE", "boolean-directive-variable",
                new Invocation.RequestWithVariables(
                        "query Fetch($withTitle: Boolean!) { article(id: 1) { id title @include(if: $withTitle) } }",
                        "Fetch", "{\"withTitle\":false}", "{}", 10L, "reader")));
        cases.add(execute("QC5-DIRECTIVES-VARIABLE", "variable-directive-requires-request-path",
                "query {\n  article(id: 1) {\n    title @skip(if: $skipTitle)\n  }\n}\n"));

        // --- QC6: filters, sorting, cursors -----------------------------------------------------
        cases.add(execute("QC6-FILTER-SCALAR", "title-eq-filter",
                "{ articles(filter: { title: { eq: \"Titan GraphQL proof\" } }, first: 2) { edges { node { id title } } } }"));
        cases.add(execute("QC6-FILTER-SCALAR", "string-operator-filters",
                "{\n  articles(\n    filter: {\n      title: {\n        contains: \"GraphQL\"\n"
                        + "        startsWith: \"Titan\"\n        endsWith: \"proof\"\n      }\n    }\n    first: 2\n  ) {\n"
                        + "    edges { node { id title } }\n  }\n}\n"));
        cases.add(execute("QC6-FILTER-SCALAR", "int-comparison-filters",
                "{\n  articles(\n    filter: {\n      id: { gt: 1, gte: 2 }\n      authorId: { lt: 12, lte: 11 }\n    }\n"
                        + "    first: 2\n  ) {\n    edges { node { id title } }\n  }\n}\n"));
        cases.add(execute("QC6-FILTER-SCALAR", "filter-composition-with-order",
                "{\n  articles(\n    filter: {\n      or: [\n        { authorId: { eq: 10 } }\n"
                        + "        { title: { in: [\"Stored functions as APIs\"] } }\n      ]\n"
                        + "      authorId: { neq: 999 }\n      not: { id: { eq: 404 } }\n      id: { isNull: false }\n    }\n"
                        + "    orderBy: [{ title: ASC }]\n    first: 2\n  ) {\n    edges { node { id title } }\n  }\n}\n"));
        cases.add(execute("QC6-FILTER-SCALAR", "empty-or-filter",
                "{ articles(filter: { or: [] }, first: 2) { edges { node { id title } } } }"));
        cases.add(execute("QC6-FILTER-SCALAR", "string-operator-on-int-rejected",
                "{ articles(filter: { id: { contains: \"1\" } }, first: 2) { edges { node { id } } } }"));
        cases.add(execute("QC6-FILTER-SCALAR", "numeric-operator-on-string-rejected",
                "{ articles(filter: { title: { gt: 1 } }, first: 2) { edges { node { id } } } }"));
        cases.add(execute("QC6-FILTER-SCALAR", "unknown-filter-field-rejected",
                "{ articles(filter: { unknown: { eq: 1 } }, first: 2) { edges { node { id } } } }"));
        cases.add(execute("QC6-FILTER-RELATION", "author-name-eq-filter",
                "{ articles(filter: { authorName: { eq: \"Ada Lovelace\" } }, first: 2) { edges { node { id title } } } }"));
        cases.add(execute("QC6-FILTER-RELATION", "relation-hop-filter-composition",
                "{\n  articles(\n    filter: {\n      authorName: { startsWith: \"Grace\", endsWith: \"Hopper\" }\n"
                        + "      not: { title: { contains: \"GraphQL\" } }\n    }\n    first: 2\n  ) {\n"
                        + "    edges { node { id title } }\n  }\n}\n"));
        cases.add(execute("QC6-SORT-LOCAL", "local-sort-title-desc",
                "{ articles(orderBy: [{ title: DESC }], first: 2) { edges { node { id title } } } }"));
        cases.add(execute("QC6-SORT-LOCAL", "local-sort-id-desc",
                "{ articles(orderBy: [{ id: DESC }], first: 2) { edges { node { id } } } }"));
        cases.add(execute("QC6-SORT-RELATION", "relation-sort-author-name-desc",
                "{ articles(orderBy: [{ authorName: DESC }], first: 2) { edges { node { id } } } }"));
        cases.add(execute("QC6-SORT-RELATION", "relation-sort-author-name-asc",
                "{ articles(orderBy: [{ authorName: ASC }], first: 2) { edges { node { id } } } }"));
        cases.add(execute("QC6-SORT-CURSOR", "generated-order-with-relay-cursor",
                "{ articles(orderBy: [{ title: ASC }], after: \"article:2\", first: 1) { edges { node { id } } "
                        + "pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }"));

        // --- QC7: Relay totalCount ----------------------------------------------------------------
        cases.add(execute("QC7-TOTAL-COUNT-ROOT", "root-total-count",
                "{ articles(first: 2) { edges { node { id title } } totalCount } }"));
        cases.add(execute("QC7-TOTAL-COUNT-ROOT", "root-total-count-filtered",
                "{ articles(filter: { authorId: { eq: 10 } }, first: 2) { edges { node { id } } totalCount } }"));
        cases.add(execute("QC7-TOTAL-COUNT-RELATION", "relation-total-count-with-page-info",
                "{article(id:1){comments(first:1){edges{cursor node{id}} totalCount "
                        + "pageInfo{hasNextPage hasPreviousPage startCursor endCursor}}}}"));
        cases.add(execute("QC7-TOTAL-COUNT-RELATION", "relation-after-cursor",
                "{article(id:1){comments(first:1, after:\"comment:100\"){edges{cursor node{id}} "
                        + "pageInfo{hasNextPage hasPreviousPage startCursor endCursor}}}}"));

        // --- QC8: computed fields -------------------------------------------------------------------
        cases.add(execute("QC8-COMPUTED-SELECT", "computed-title-length",
                "{ article(id: 1) { title titleLength } }"));
        cases.add(execute("QC8-COMPUTED-FILTER-SORT", "computed-filter-and-order",
                "{\n  articles(\n    filter: { titleLength: { gt: 18 } }\n"
                        + "    orderBy: [{ titleLength: DESC }, { id: ASC }]\n    first: 2\n  ) {\n"
                        + "    edges { node { id titleLength } }\n  }\n}\n"));

        // --- QC9: context filters ---------------------------------------------------------------------
        cases.add(new Case("QC9-CONTEXT-FILTER", "published-visibility-rows-and-counts",
                new Invocation.WithContext(
                        "{ articles(first: 2) { edges { node { id title } } totalCount } }",
                        10L, "reader", true, true, true)));
        cases.add(new Case("QC9-CONTEXT-FILTER", "visibility-composed-with-client-filters",
                new Invocation.WithContext(
                        "{ articles(first: 2, filter: {authorId: {eq: 11}}) { edges { node { id title } } totalCount } }",
                        10L, "reader", true, true, true)));
        cases.add(new Case("QC9-CONTEXT-FILTER", "visibility-fails-closed-without-key",
                new Invocation.WithContext(
                        "{ articles(first: 2) { edges { node { id } } totalCount } }",
                        10L, "reader", true, false, false)));
        cases.add(new Case("QC9-CONTEXT-FILTER", "visibility-cursor-window",
                new Invocation.WithContext(
                        "{ articles(first: 2, after: \"article:1\") { edges { node { id } } totalCount "
                                + "pageInfo { hasNextPage hasPreviousPage startCursor endCursor } } }",
                        10L, "reader", true, true, true)));
        cases.add(new Case("QC9-CONTEXT-FILTER", "compact-context-enabled-filter-names",
                new Invocation.CompactContext(
                        "{ articles(first: 2) { edges { node { id title } } totalCount } }",
                        "", "", "", 10L, "reader", false, true, true, false,
                        "tenant-a", "request-7", "can-preview", "tenantIsolation, publishedVisibility", 2500L)));

        // --- QC10: introspection -------------------------------------------------------------------------
        cases.add(introspection("QC10-INTROSPECTION-STABLE", "schema-smoke-subset",
                "{ __schema { queryType { name } types { name kind } } }"));
        cases.add(introspection("QC10-INTROSPECTION-STABLE", "type-smoke-subset",
                "{ __type(name: \"Article\") { name kind fields { name type { name kind } } } }"));
        cases.add(introspection("QC10-INTROSPECTION-SCHEMA-NULLABLES", "schema-nullable-metadata",
                "{ __schema {\n    description\n    mutationType { name }\n    subscriptionType { name }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-FIELD-ARGS", "root-field-args",
                "{ __type(name: \"Query\") { fields { name args { name type { name kind } } } } }"));
        cases.add(introspection("QC10-INTROSPECTION-FIELD-ARGS", "relation-field-args",
                "{ __type(name: \"Article\") { fields { name args { name type { name kind } } } } }"));
        cases.add(introspection("QC10-INTROSPECTION-TYPE-WRAPPERS", "query-type-wrappers",
                "{ __type(name: \"Query\") {\n    fields {\n      name\n      type { name kind ofType { name kind } }\n"
                        + "      args { name type { name kind ofType { name kind ofType { name kind } } } }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-TYPE-WRAPPERS", "relation-type-wrappers",
                "{ __type(name: \"Article\") {\n    fields {\n      name\n      type { name kind ofType { name kind } }\n"
                        + "    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INPUT-FIELDS", "article-filter-input-fields",
                "{ __type(name: \"ArticleFilter\") {\n    name\n    kind\n    inputFields {\n      name\n"
                        + "      type { name kind ofType { name kind ofType { name kind } } }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INPUT-FIELDS", "int-filter-input-fields",
                "{ __type(name: \"IntFilter\") {\n    inputFields {\n      name\n      type { name kind ofType { name kind } }\n"
                        + "    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INPUT-FIELDS", "order-input-fields",
                "{ __type(name: \"ArticleOrderBy\") {\n    inputFields {\n      name\n      type { name kind }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-ENUM-VALUES", "sort-direction-enum-values",
                "{ __type(name: \"SortDirection\") {\n    name\n    kind\n    enumValues {\n      name\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INCLUDE-DEPRECATED", "fields-include-deprecated",
                "{ __type(name: \"Article\") {\n    fields(includeDeprecated: true) {\n      name\n"
                        + "      type { name kind }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INCLUDE-DEPRECATED", "enum-values-include-deprecated",
                "{ __type(name: \"SortDirection\") {\n    enumValues(includeDeprecated: false) {\n      name\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INPUT-INCLUDE-DEPRECATED", "input-fields-include-deprecated",
                "{ __type(name: \"ArticleOrderBy\") {\n    inputFields(includeDeprecated: false) {\n      name\n"
                        + "      defaultValue\n      type { name kind }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-DEFAULT-VALUES", "argument-default-values",
                "{ __type(name: \"Query\") {\n    fields {\n      name\n      args {\n        name\n        defaultValue\n"
                        + "        type { name kind }\n      }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-DEFAULT-VALUES", "input-field-default-values",
                "{ __type(name: \"ArticleFilter\") {\n    inputFields {\n      name\n      defaultValue\n"
                        + "      type { name kind }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-DESCRIPTIONS", "enum-descriptions",
                "{ __type(name: \"SortDirection\") {\n    name\n    description\n    enumValues {\n      name\n"
                        + "      description\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-DESCRIPTIONS", "nested-descriptions",
                "{ __type(name: \"Query\") {\n    fields {\n      name\n      description\n"
                        + "      type { name kind description }\n      args {\n        name\n        description\n"
                        + "        type { name kind description }\n      }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-DEPRECATIONS", "field-deprecations",
                "{ __type(name: \"Article\") {\n    fields(includeDeprecated: true) {\n      name\n      isDeprecated\n"
                        + "      deprecationReason\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-DEPRECATIONS", "enum-deprecations",
                "{ __type(name: \"SortDirection\") {\n    enumValues(includeDeprecated: true) {\n      name\n"
                        + "      isDeprecated\n      deprecationReason\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INPUT-DEPRECATIONS", "argument-deprecations",
                "{ __type(name: \"Query\") {\n    fields {\n      name\n      args {\n        name\n        isDeprecated\n"
                        + "        deprecationReason\n      }\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-INPUT-DEPRECATIONS", "input-field-deprecations",
                "{ __type(name: \"ArticleFilter\") {\n    inputFields(includeDeprecated: true) {\n      name\n"
                        + "      isDeprecated\n      deprecationReason\n    }\n  }\n}\n"));
        cases.add(introspection("QC10-INTROSPECTION-DIRECTIVES", "schema-directives",
                "{ __schema {\n    directives {\n      name\n      description\n      isRepeatable\n      locations\n"
                        + "      args {\n        name\n        type { name kind ofType { name kind } }\n      }\n    }\n  }\n}\n"));
        cases.add(execute("QC10-INTROSPECTION-DISABLE", "schema-introspection-disabled-by-default",
                "{ __schema { queryType { name } } }"));
        cases.add(execute("QC10-INTROSPECTION-DISABLE", "type-introspection-disabled-by-default",
                "{ __type(name: \"Article\") { name } }"));
        cases.add(new Case("QC10-INTROSPECTION-DISABLE", "introspection-explicitly-disabled",
                new Invocation.WithIntrospection(
                        "{ __schema { queryType { name } } }", 10L, "reader", false)));

        // --- QC11: Quarkus HTTP bridge delegation ----------------------------------------------------------
        // The HTTP resource is Java transport; what it delegates into is the lowered compact-context
        // entry point with exactly these argument tuples (GraphqlHttpResource -> DemoBlogGraphqlRuntime).
        // These cases prove the delegated execution path on live SQL; media negotiation itself stays
        // Java-side by design.
        cases.add(new Case("QC11-QUARKUS-POST", "post-delegated-envelope",
                new Invocation.CompactContext(
                        "query Pick($id: ID!, $visible: Boolean!) {\n"
                                + "  article(id: $id) {\n    id\n    title @include(if: $visible)\n  }\n}\n",
                        "Pick", "{\"id\":\"1\",\"visible\":true}", "{}",
                        0L, "reader", false, false, false, false, "", "", "", "", 0L)));
        cases.add(new Case("QC11-QUARKUS-GET", "get-delegated-query-only-request",
                new Invocation.CompactContext(
                        "query One {\n  article(id: 2) { id title }\n}\n"
                                + "query Pick($id: ID!, $showTitle: Boolean!) {\n"
                                + "  article(id: $id) {\n    id\n    title @include(if: $showTitle)\n  }\n}\n",
                        "Pick", "{\"id\":\"1\",\"showTitle\":true}", "{}",
                        0L, "reader", false, false, false, false, "", "", "", "", 0L)));

        return List.copyOf(cases);
    }

    private static Case execute(String rowId, String name, String query) {
        return execute(rowId, name, query, 10L, "reader");
    }

    private static Case execute(String rowId, String name, String query, long actorId, String actorRole) {
        return new Case(rowId, name, new Invocation.Execute(query, actorId, actorRole));
    }

    private static Case introspection(String rowId, String name, String query) {
        return new Case(rowId, name, new Invocation.WithIntrospection(query, 10L, "reader", true));
    }
}
