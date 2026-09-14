package io.titan.graphql.demo.blog;

import io.titan.graphql.*;

import static io.titan.graphql.catalog.public_.tables.Articles.ARTICLES;
import static io.titan.graphql.catalog.public_.tables.Comments.COMMENTS;
import static io.titan.graphql.catalog.public_.tables.Users.USERS;
import static titan.dsl.DSL.select;

import java.util.ArrayList;
import java.util.List;

public final class DemoBlogGraphqlExecutor {

    private final GraphqlSchema schema;
    private final DemoBlogFixtureStore store;
    private final GraphqlJsonWriter jsonWriter;

    public DemoBlogGraphqlExecutor(GraphqlSchema schema, DemoBlogFixtureStore store, GraphqlJsonWriter jsonWriter) {
        this.schema = schema;
        this.store = store;
        this.jsonWriter = jsonWriter;
    }

    public GraphqlExecution execute(GraphqlSelection selection, GraphqlRequestContext context) {
        return executeSelection(schema, store, jsonWriter, selection, context);
    }

    private static GraphqlExecution executeSelection(
            GraphqlSchema schema,
            DemoBlogFixtureStore store,
            GraphqlJsonWriter jsonWriter,
            GraphqlSelection selection,
            GraphqlRequestContext context
    ) {
        GraphqlPlan plan = new GraphqlPlan();
        GraphqlReadPlan readPlan = GraphqlReadPlanner.plan(schema, selection);
        if (selection.rootFieldName().equals("articles")) {
            return executeArticles(store, selection, readPlan, plan, context);
        }
        if (selection.rootFieldName().equals("article") == false) {
            throw new GraphqlException("execution model does not support root field '" + selection.rootFieldName() + "'");
        }
        return executeArticle(store, selection, readPlan, plan);
    }

    private static GraphqlExecution executeArticle(
            DemoBlogFixtureStore store,
            GraphqlSelection selection,
            GraphqlReadPlan readPlan,
            GraphqlPlan plan
    ) {
        plan.addReadStep(
                readPlan.rootRead().stepName(),
                select(ARTICLES.ID, ARTICLES.AUTHOR_ID, ARTICLES.TITLE)
                        .from(ARTICLES)
                        .where(ARTICLES.ID.eq(selection.rootId()))
                        .fetchOne()
        );

        DemoBlogFixtureStore.ArticleRow article = store.article(selection.rootId());
        DemoBlogFixtureStore.UserRow author = null;
        List<DemoBlogFixtureStore.CommentRow> comments = List.of();
        if (selection.includesRelation("author") && article != null) {
            GraphqlReadPlan.RelationRead authorRead = readPlan.relationRead(selection.rootFieldName() + ".author");
            plan.addReadStep(
                    authorRead.stepName(),
                    select(USERS.ID, USERS.NAME, USERS.EMAIL)
                            .from(USERS)
                            .where(USERS.ID.eq(article.authorId()))
                            .fetchOne()
            );
            author = store.user(article.authorId());
        }
        if (selection.includesRelation("comments") && article != null) {
            GraphqlReadPlan.RelationRead commentsRead = readPlan.relationRead(selection.rootFieldName() + ".comments");
            validateSupportedCommentConnection(commentsRead);
            plan.addReadStep(
                    commentsRead.stepName(),
                    select(COMMENTS.ID, COMMENTS.ARTICLE_ID, COMMENTS.AUTHOR_ID, COMMENTS.BODY)
                            .from(COMMENTS)
                            .where(COMMENTS.ARTICLE_ID.eq(article.id()))
                            .fetch()
            );
            comments = store.commentsForArticle(article.id());
            addCommentAuthorReadStep(selection, readPlan, plan, comments);
        }
        return new GraphqlExecution(DemoBlogGraphqlJsonWriter.article(selection, article, author, comments, store), plan);
    }

    private static GraphqlExecution executeArticles(
            DemoBlogFixtureStore store,
            GraphqlSelection selection,
            GraphqlReadPlan readPlan,
            GraphqlPlan plan,
            GraphqlRequestContext context
    ) {
        List<DemoBlogFixtureStore.ArticleRow> candidates = generatedRootArticles(store, readPlan, context);
        Long authorId = selection.rootFilterValue("authorId");
        candidates = legacyAuthorFilter(candidates, authorId);
        if (selection.rootConnectionSelection().selected()) {
            return executeArticlesConnection(store, selection, readPlan, plan, candidates, authorId);
        }
        if (authorId == null) {
            plan.addReadStep(
                    readPlan.rootRead().stepName(),
                    select(ARTICLES.ID, ARTICLES.AUTHOR_ID, ARTICLES.TITLE)
                            .from(ARTICLES)
                            .orderBy(ARTICLES.ID.asc())
                            .limit(selection.rootLimit())
                            .fetch()
            );
        } else {
            plan.addReadStep(
                    readPlan.rootRead().stepName(),
                    select(ARTICLES.ID, ARTICLES.AUTHOR_ID, ARTICLES.TITLE)
                            .from(ARTICLES)
                            .where(ARTICLES.AUTHOR_ID.eq(authorId))
                            .orderBy(ARTICLES.ID.asc())
                            .limit(selection.rootLimit())
                            .fetch()
            );
        }
        List<DemoBlogFixtureStore.ArticleRow> articles = limit(candidates, selection.rootLimit());
        if (selection.includesRelation("author") && articles.isEmpty() == false) {
            GraphqlReadPlan.RelationRead authorRead = readPlan.relationRead(selection.rootFieldName() + ".author");
            plan.addReadStep(
                    authorRead.stepName(),
                    select(USERS.ID, USERS.NAME, USERS.EMAIL)
                            .from(USERS)
                            .where(USERS.ID.in(authorIds(articles)))
                            .fetch()
            );
        }
        if (selection.includesRelation("comments") && articles.isEmpty() == false) {
            GraphqlReadPlan.RelationRead commentsRead = readPlan.relationRead(selection.rootFieldName() + ".comments");
            validateSupportedCommentConnection(commentsRead);
            plan.addReadStep(
                    commentsRead.stepName(),
                    select(COMMENTS.ID, COMMENTS.ARTICLE_ID, COMMENTS.AUTHOR_ID, COMMENTS.BODY)
                            .from(COMMENTS)
                            .where(COMMENTS.ARTICLE_ID.in(articleIds(articles)))
                            .fetch()
            );
            addCommentAuthorReadStep(selection, readPlan, plan, commentsForArticles(store, articles));
        }
        return new GraphqlExecution(DemoBlogGraphqlJsonWriter.articles(selection, articles, store), plan);
    }

    private static GraphqlExecution executeArticlesConnection(
            DemoBlogFixtureStore store,
            GraphqlSelection selection,
            GraphqlReadPlan readPlan,
            GraphqlPlan plan,
            List<DemoBlogFixtureStore.ArticleRow> candidates,
            Long authorId
    ) {
        if (selection.rootCursorOrdering().columnName().equals("id") == false
                || selection.rootCursorOrdering().direction() != GraphqlRootField.RootCursorDirection.ASC) {
            throw new GraphqlException("execution model supports Relay root cursors ordered by id ascending");
        }
        int fetchLimit = readPlan.rootRead().cursorWindow().fetchRowCount();
        if (authorId == null) {
            plan.addReadStep(
                    readPlan.rootRead().stepName(),
                    select(ARTICLES.ID, ARTICLES.AUTHOR_ID, ARTICLES.TITLE)
                            .from(ARTICLES)
                            .orderBy(ARTICLES.ID.asc())
                            .limit(fetchLimit)
                            .fetch()
            );
        } else {
            plan.addReadStep(
                    readPlan.rootRead().stepName(),
                    select(ARTICLES.ID, ARTICLES.AUTHOR_ID, ARTICLES.TITLE)
                            .from(ARTICLES)
                            .where(ARTICLES.AUTHOR_ID.eq(authorId))
                            .orderBy(ARTICLES.ID.asc())
                            .limit(fetchLimit)
                            .fetch()
            );
        }
        ConnectionPage page = articleConnectionPage(store, candidates, selection);
        List<DemoBlogFixtureStore.ArticleRow> articles = page.articles();
        if (selection.includesRelation("author") && articles.isEmpty() == false) {
            GraphqlReadPlan.RelationRead authorRead = readPlan.relationRead(selection.rootFieldName() + ".author");
            plan.addReadStep(
                    authorRead.stepName(),
                    select(USERS.ID, USERS.NAME, USERS.EMAIL)
                            .from(USERS)
                            .where(USERS.ID.in(authorIds(articles)))
                            .fetch()
            );
        }
        if (selection.includesRelation("comments") && articles.isEmpty() == false) {
            GraphqlReadPlan.RelationRead commentsRead = readPlan.relationRead(selection.rootFieldName() + ".comments");
            validateSupportedCommentConnection(commentsRead);
            plan.addReadStep(
                    commentsRead.stepName(),
                    select(COMMENTS.ID, COMMENTS.ARTICLE_ID, COMMENTS.AUTHOR_ID, COMMENTS.BODY)
                            .from(COMMENTS)
                            .where(COMMENTS.ARTICLE_ID.in(articleIds(articles)))
                            .fetch()
            );
            addCommentAuthorReadStep(selection, readPlan, plan, commentsForArticles(store, articles));
        }
        return new GraphqlExecution(
                DemoBlogGraphqlJsonWriter.articlesConnection(
                        selection,
                        articles,
                        candidates.size(),
                        store,
                        page.hasNextPage(),
                        page.hasPreviousPage()
                ),
                plan
        );
    }

    private static ConnectionPage articleConnectionPage(
            DemoBlogFixtureStore store,
            List<DemoBlogFixtureStore.ArticleRow> candidates,
            GraphqlSelection selection
    ) {
        List<DemoBlogFixtureStore.ArticleRow> window = new ArrayList<>();
        for (DemoBlogFixtureStore.ArticleRow article : candidates) {
            if (articleAfterCursor(store, article, selection.rootPagination().afterCursor(), selection.rootOrderBy())
                    && articleBeforeCursor(store, article, selection.rootPagination().beforeCursor(), selection.rootOrderBy())) {
                window.add(article);
            }
        }
        boolean hasPreviousPage = selection.rootPagination().afterCursor() != null;
        boolean hasNextPage = selection.rootPagination().beforeCursor() != null;
        int requested = selection.rootLimit();
        if (selection.rootPagination().last() != null) {
            int from = Math.max(0, window.size() - requested);
            hasPreviousPage = hasPreviousPage || from > 0;
            return new ConnectionPage(List.copyOf(window.subList(from, window.size())), hasNextPage, hasPreviousPage);
        }
        int to = Math.min(window.size(), requested);
        hasNextPage = hasNextPage || window.size() > requested;
        return new ConnectionPage(List.copyOf(window.subList(0, to)), hasNextPage, hasPreviousPage);
    }

    private static void validateSupportedCommentConnection(GraphqlReadPlan.RelationRead commentsRead) {
        if (commentsRead.connectionSelection().selected() == false) {
            return;
        }
        GraphqlFieldDescriptor.RelationSortPath ordering = commentsRead.sortPaths().getFirst();
        if (ordering.columnName().equals("id") == false
                || ordering.direction() != GraphqlFieldDescriptor.RelationSortDirection.ASC
                || ordering.tieBreakerColumnName().equals("id") == false) {
            throw new GraphqlException("execution model supports Relay comment cursors ordered by id ascending");
        }
    }

    private static long cursorId(GraphqlCursorCodec.CursorPayload cursor, long defaultValue) {
        if (cursor == null) {
            return defaultValue;
        }
        return Long.parseLong(cursor.tieBreakerValue());
    }

    private static boolean articleAfterCursor(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlCursorCodec.CursorPayload cursor,
            List<GraphqlSelection.RootOrder> orderBy
    ) {
        if (cursor == null) {
            return true;
        }
        if (orderBy.isEmpty()) {
            return article.id() > cursorId(cursor, Long.MIN_VALUE);
        }
        return compareArticleToCursor(store, article, cursor, orderBy.getFirst()) > 0;
    }

    private static boolean articleBeforeCursor(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlCursorCodec.CursorPayload cursor,
            List<GraphqlSelection.RootOrder> orderBy
    ) {
        if (cursor == null) {
            return true;
        }
        if (orderBy.isEmpty()) {
            return article.id() < cursorId(cursor, Long.MAX_VALUE);
        }
        return compareArticleToCursor(store, article, cursor, orderBy.getFirst()) < 0;
    }

    private static int compareArticleToCursor(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlCursorCodec.CursorPayload cursor,
            GraphqlSelection.RootOrder order
    ) {
        int comparison = switch (order.columnName()) {
            case "id", "titleLength" -> Long.compare(articleLongOrderValue(article, order), Long.parseLong(cursor.value()));
            case "author_id" -> order.sortPath().equals("author.name")
                    ? articleStringValue(store, article, order).compareTo(cursor.value())
                    : Long.compare(articleLongOrderValue(article, order), Long.parseLong(cursor.value()));
            case "title" -> article.title().compareTo(cursor.value());
            default -> throw new GraphqlException("execution model does not support generated cursor order path '"
                    + order.name() + "'");
        };
        if (comparison == 0) {
            comparison = Long.compare(article.id(), Long.parseLong(cursor.tieBreakerValue()));
        }
        return order.direction() == GraphqlRootField.RootCursorDirection.DESC ? -comparison : comparison;
    }

    private static long articleLongOrderValue(
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.RootOrder order
    ) {
        return switch (order.columnName()) {
            case "id" -> article.id();
            case "author_id" -> article.authorId();
            case "titleLength" -> article.title().length();
            default -> throw new GraphqlException("execution model does not support generated numeric cursor order path '"
                    + order.name() + "'");
        };
    }

    private static void addCommentAuthorReadStep(
            GraphqlSelection selection,
            GraphqlReadPlan readPlan,
            GraphqlPlan plan,
            List<DemoBlogFixtureStore.CommentRow> comments
    ) {
        GraphqlSelection.FieldSelection commentsSelection = selection.relation("comments");
        if (commentsSelection == null || relation(commentsSelection, "author") == null || comments.isEmpty()) {
            return;
        }
        comments = commentsForNestedReads(commentsSelection, comments);
        if (comments.isEmpty()) {
            return;
        }
        GraphqlReadPlan.RelationRead authorRead = readPlan.relationRead(selection.rootFieldName() + ".comments.author");
        plan.addReadStep(
                authorRead.stepName(),
                select(USERS.ID, USERS.NAME, USERS.EMAIL)
                        .from(USERS)
                        .where(USERS.ID.in(commentAuthorIds(comments)))
                        .fetch()
        );
    }

    private static GraphqlSelection.FieldSelection relation(GraphqlSelection.FieldSelection selection, String name) {
        for (GraphqlSelection.FieldSelection nested : selection.selections()) {
            if (nested.kind() == GraphqlFieldDescriptor.FieldKind.RELATION && nested.name().equals(name)) {
                return nested;
            }
        }
        return null;
    }

    private static List<DemoBlogFixtureStore.CommentRow> commentsForNestedReads(
            GraphqlSelection.FieldSelection selection,
            List<DemoBlogFixtureStore.CommentRow> comments
    ) {
        if (selection.relationConnectionSelection().selected() == false) {
            return comments;
        }
        List<DemoBlogFixtureStore.CommentRow> paged = new ArrayList<>();
        List<Long> articleIds = new ArrayList<>();
        for (DemoBlogFixtureStore.CommentRow comment : comments) {
            if (articleIds.contains(comment.articleId()) == false) {
                articleIds.add(comment.articleId());
            }
        }
        for (Long articleId : articleIds) {
            paged.addAll(commentConnectionPage(commentsForArticle(comments, articleId), selection));
        }
        return List.copyOf(paged);
    }

    private static List<DemoBlogFixtureStore.CommentRow> commentsForArticle(
            List<DemoBlogFixtureStore.CommentRow> comments,
            long articleId
    ) {
        List<DemoBlogFixtureStore.CommentRow> rows = new ArrayList<>();
        for (DemoBlogFixtureStore.CommentRow comment : comments) {
            if (comment.articleId() == articleId) {
                rows.add(comment);
            }
        }
        return List.copyOf(rows);
    }

    private static List<DemoBlogFixtureStore.CommentRow> commentConnectionPage(
            List<DemoBlogFixtureStore.CommentRow> comments,
            GraphqlSelection.FieldSelection selection
    ) {
        long afterId = relationCursorId(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_AFTER, Long.MIN_VALUE);
        long beforeId = relationCursorId(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_BEFORE, Long.MAX_VALUE);
        List<DemoBlogFixtureStore.CommentRow> window = new ArrayList<>();
        for (DemoBlogFixtureStore.CommentRow comment : comments) {
            if (comment.id() > afterId && comment.id() < beforeId) {
                window.add(comment);
            }
        }
        int requested = relationPageSize(selection);
        if (relationArgument(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_LAST) != null) {
            int from = Math.max(0, window.size() - requested);
            return List.copyOf(window.subList(from, window.size()));
        }
        int to = Math.min(window.size(), requested);
        return List.copyOf(window.subList(0, to));
    }

    private static int relationPageSize(GraphqlSelection.FieldSelection selection) {
        return selection.relationConnectionSelection().pageSize();
    }

    private static long relationCursorId(
            GraphqlSelection.FieldSelection selection,
            GraphqlRelationArgumentDescriptor.RelationArgumentKind kind,
            long defaultValue
    ) {
        GraphqlCursorCodec.CursorPayload cursor = relationCursor(selection, kind);
        return cursor == null ? defaultValue : Long.parseLong(cursor.tieBreakerValue());
    }

    private static GraphqlCursorCodec.CursorPayload relationCursor(
            GraphqlSelection.FieldSelection selection,
            GraphqlRelationArgumentDescriptor.RelationArgumentKind kind
    ) {
        GraphqlSelection.RelationArgument argument = relationArgument(selection, kind);
        return argument == null ? null : argument.cursorPayload();
    }

    private static GraphqlSelection.RelationArgument relationArgument(
            GraphqlSelection.FieldSelection selection,
            GraphqlRelationArgumentDescriptor.RelationArgumentKind kind
    ) {
        for (GraphqlSelection.RelationArgument argument : selection.relationArguments()) {
            if (argument.kind() == kind) {
                return argument;
            }
        }
        return null;
    }

    private static List<DemoBlogFixtureStore.CommentRow> commentsForArticles(
            DemoBlogFixtureStore store,
            List<DemoBlogFixtureStore.ArticleRow> articles
    ) {
        List<DemoBlogFixtureStore.CommentRow> comments = new ArrayList<>();
        for (DemoBlogFixtureStore.ArticleRow article : articles) {
            comments.addAll(store.commentsForArticle(article.id()));
        }
        return List.copyOf(comments);
    }

    private static List<Long> articleIds(List<DemoBlogFixtureStore.ArticleRow> articles) {
        List<Long> ids = new ArrayList<>();
        for (DemoBlogFixtureStore.ArticleRow article : articles) {
            ids.add(article.id());
        }
        return List.copyOf(ids);
    }

    private static List<Long> authorIds(List<DemoBlogFixtureStore.ArticleRow> articles) {
        List<Long> ids = new ArrayList<>();
        for (DemoBlogFixtureStore.ArticleRow article : articles) {
            if (ids.contains(article.authorId()) == false) {
                ids.add(article.authorId());
            }
        }
        return List.copyOf(ids);
    }

    private static List<DemoBlogFixtureStore.ArticleRow> generatedRootArticles(
            DemoBlogFixtureStore store,
            GraphqlReadPlan readPlan,
            GraphqlRequestContext context
    ) {
        List<DemoBlogFixtureStore.ArticleRow> rows = new ArrayList<>(store.articlesByAuthor(-1L));
        rows.removeIf(article -> matchesContextFilters(article, readPlan.rootRead().contextFilters(), context) == false);
        rows.removeIf(article -> matchesGeneratedRootFilters(store, article, readPlan.rootRead().generatedFilters()) == false);
        sortGeneratedRootArticles(store, rows, readPlan.rootRead().orderBy());
        return List.copyOf(rows);
    }

    private static boolean matchesContextFilters(
            DemoBlogFixtureStore.ArticleRow article,
            List<GraphqlRootField.RootContextFilter> filters,
            GraphqlRequestContext context
    ) {
        for (GraphqlRootField.RootContextFilter filter : filters) {
            if (matchesContextFilter(article, filter, context) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesContextFilter(
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlRootField.RootContextFilter filter,
            GraphqlRequestContext context
    ) {
        if (context.contextFilterEnabled(filter.name()) == false) {
            return true;
        }
        if (filter.name().equals("publishedVisibility") == false
                || filter.columnName().equals("published") == false
                || filter.contextKey().equals("articleVisibility") == false
                || filter.valueType() != GraphqlRootField.RootContextFilterValueType.BOOLEAN) {
            throw new GraphqlException("execution model does not support context filter '" + filter.name() + "'");
        }
        if (context.hasArticleVisibility() == false) {
            return false;
        }
        return article.published() == context.articleVisibility();
    }

    private static List<DemoBlogFixtureStore.ArticleRow> legacyAuthorFilter(
            List<DemoBlogFixtureStore.ArticleRow> rows,
            Long authorId
    ) {
        if (authorId == null) {
            return rows;
        }
        List<DemoBlogFixtureStore.ArticleRow> filtered = new ArrayList<>();
        for (DemoBlogFixtureStore.ArticleRow article : rows) {
            if (article.authorId() == authorId) {
                filtered.add(article);
            }
        }
        return List.copyOf(filtered);
    }

    private static List<DemoBlogFixtureStore.ArticleRow> limit(
            List<DemoBlogFixtureStore.ArticleRow> rows,
            int limit
    ) {
        if (limit >= rows.size()) {
            return rows;
        }
        return List.copyOf(rows.subList(0, limit));
    }

    private static boolean matchesGeneratedRootFilters(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            List<GraphqlSelection.GeneratedRootFilter> filters
    ) {
        for (GraphqlSelection.GeneratedRootFilter filter : filters) {
            if (matchesGeneratedRootFilter(store, article, filter) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesGeneratedRootFilter(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        return switch (filter.kind()) {
            case SCALAR -> matchesScalarGeneratedRootFilter(store, article, filter);
            case AND -> matchesAllGeneratedRootFilters(store, article, filter.children());
            case OR -> matchesAnyGeneratedRootFilter(store, article, filter.children());
            case NOT -> matchesGeneratedRootFilter(store, article, filter.children().getFirst()) == false;
        };
    }

    private static boolean matchesAllGeneratedRootFilters(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            List<GraphqlSelection.GeneratedRootFilter> filters
    ) {
        for (GraphqlSelection.GeneratedRootFilter filter : filters) {
            if (matchesGeneratedRootFilter(store, article, filter) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAnyGeneratedRootFilter(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            List<GraphqlSelection.GeneratedRootFilter> filters
    ) {
        for (GraphqlSelection.GeneratedRootFilter filter : filters) {
            if (matchesGeneratedRootFilter(store, article, filter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesScalarGeneratedRootFilter(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        return switch (filter.operator()) {
            case EQ -> compareGeneratedRootValue(store, article, filter, filter.values().getFirst());
            case NEQ -> compareGeneratedRootValue(store, article, filter, filter.values().getFirst()) == false;
            case IN -> matchesAnyGeneratedRootValue(store, article, filter);
            case IS_NULL -> filter.values().getFirst().booleanValue() == false;
            case LT -> articleIntValue(article, filter) < filter.values().getFirst().intValue();
            case LTE -> articleIntValue(article, filter) <= filter.values().getFirst().intValue();
            case GT -> articleIntValue(article, filter) > filter.values().getFirst().intValue();
            case GTE -> articleIntValue(article, filter) >= filter.values().getFirst().intValue();
            case CONTAINS -> articleStringValue(store, article, filter).contains(filter.values().getFirst().stringValue());
            case STARTS_WITH -> articleStringValue(store, article, filter).startsWith(filter.values().getFirst().stringValue());
            case ENDS_WITH -> articleStringValue(store, article, filter).endsWith(filter.values().getFirst().stringValue());
        };
    }

    private static long articleIntValue(
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        return switch (filter.columnName()) {
            case "id" -> article.id();
            case "author_id" -> article.authorId();
            case "titleLength" -> article.title().length();
            default -> throw new GraphqlException("execution model does not support generated numeric filter field '"
                    + filter.fieldName() + "'");
        };
    }

    private static String articleStringValue(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        if (filter.columnName().equals("title")) {
            return article.title();
        }
        if (filter.filterPath().equals("author.name")) {
            DemoBlogFixtureStore.UserRow author = store.user(article.authorId());
            return author == null ? "" : author.name();
        }
        throw new GraphqlException("execution model does not support generated string filter field '"
                + filter.fieldName() + "'");
    }

    private static String articleStringValue(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.RootOrder order
    ) {
        if (order.columnName().equals("title")) {
            return article.title();
        }
        if (order.sortPath().equals("author.name")) {
            DemoBlogFixtureStore.UserRow author = store.user(article.authorId());
            return author == null ? "" : author.name();
        }
        throw new GraphqlException("execution model does not support generated string order path '"
                + order.name() + "'");
    }

    private static boolean matchesAnyGeneratedRootValue(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.GeneratedRootFilter filter
    ) {
        for (GraphqlSelection.GeneratedRootFilterValue value : filter.values()) {
            if (compareGeneratedRootValue(store, article, filter, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compareGeneratedRootValue(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow article,
            GraphqlSelection.GeneratedRootFilter filter,
            GraphqlSelection.GeneratedRootFilterValue value
    ) {
        if (value.nullValue()) {
            return false;
        }
        return switch (filter.columnName()) {
            case "id" -> article.id() == value.intValue();
            case "author_id" -> filter.filterPath().equals("author.name")
                    ? articleStringValue(store, article, filter).equals(value.stringValue())
                    : article.authorId() == value.intValue();
            case "title" -> article.title().equals(value.stringValue());
            case "titleLength" -> article.title().length() == value.intValue();
            default -> throw new GraphqlException("execution model does not support generated filter field '"
                    + filter.fieldName() + "'");
        };
    }

    private static void sortGeneratedRootArticles(
            DemoBlogFixtureStore store,
            List<DemoBlogFixtureStore.ArticleRow> rows,
            List<GraphqlSelection.RootOrder> orderBy
    ) {
        for (int index = orderBy.size() - 1; index >= 0; index--) {
            GraphqlSelection.RootOrder order = orderBy.get(index);
            rows.sort((left, right) -> compareGeneratedRootOrder(store, left, right, order));
        }
    }

    private static int compareGeneratedRootOrder(
            DemoBlogFixtureStore store,
            DemoBlogFixtureStore.ArticleRow left,
            DemoBlogFixtureStore.ArticleRow right,
            GraphqlSelection.RootOrder order
    ) {
        int comparison = switch (order.columnName()) {
            case "id" -> Long.compare(left.id(), right.id());
            case "author_id" -> order.sortPath().equals("author.name")
                    ? articleStringValue(store, left, order).compareTo(articleStringValue(store, right, order))
                    : Long.compare(left.authorId(), right.authorId());
            case "title" -> left.title().compareTo(right.title());
            case "titleLength" -> Integer.compare(left.title().length(), right.title().length());
            default -> throw new GraphqlException("execution model does not support generated order path '"
                    + order.name() + "'");
        };
        if (comparison == 0) {
            comparison = Long.compare(left.id(), right.id());
        }
        return order.direction() == GraphqlRootField.RootCursorDirection.DESC ? -comparison : comparison;
    }

    private static List<Long> commentAuthorIds(List<DemoBlogFixtureStore.CommentRow> comments) {
        List<Long> ids = new ArrayList<>();
        for (DemoBlogFixtureStore.CommentRow comment : comments) {
            if (ids.contains(comment.authorId()) == false) {
                ids.add(comment.authorId());
            }
        }
        return List.copyOf(ids);
    }

    private record ConnectionPage(
            List<DemoBlogFixtureStore.ArticleRow> articles,
            boolean hasNextPage,
            boolean hasPreviousPage
    ) {
    }
}
