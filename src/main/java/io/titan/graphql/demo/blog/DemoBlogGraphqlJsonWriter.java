package io.titan.graphql.demo.blog;

import io.titan.graphql.*;

import java.util.Iterator;
import java.util.List;

public final class DemoBlogGraphqlJsonWriter {

    public static String article(
            GraphqlSelection selection,
            DemoBlogFixtureStore.ArticleRow article,
            DemoBlogFixtureStore.UserRow author,
            List<DemoBlogFixtureStore.CommentRow> comments,
            DemoBlogFixtureStore store
    ) {
        if (article == null) {
            return "{\"data\":{\"" + selection.rootResponseKey() + "\":null}}";
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"data\":{\"").append(selection.rootResponseKey()).append("\":{");
        appendArticle(json, selection, article, author, comments, store);
        json.append("}}}");
        return json.toString();
    }

    public static String articles(
            GraphqlSelection selection,
            List<DemoBlogFixtureStore.ArticleRow> articles,
            DemoBlogFixtureStore store
    ) {
        StringBuilder json = new StringBuilder();
        json.append("{\"data\":{\"").append(selection.rootResponseKey()).append("\":[");
        for (int i = 0; i < articles.size(); i++) {
            DemoBlogFixtureStore.ArticleRow article = articles.get(i);
            DemoBlogFixtureStore.UserRow author = selection.includesRelation("author")
                    ? store.user(article.authorId())
                    : null;
            List<DemoBlogFixtureStore.CommentRow> comments = selection.includesRelation("comments")
                    ? store.commentsForArticle(article.id())
                    : List.of();
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            appendArticle(json, selection, article, author, comments, store);
            json.append('}');
        }
        json.append("]}}");
        return json.toString();
    }

    public static String articlesConnection(
            GraphqlSelection selection,
            List<DemoBlogFixtureStore.ArticleRow> articles,
            int totalCount,
            DemoBlogFixtureStore store,
            boolean hasNextPage,
            boolean hasPreviousPage
    ) {
        StringBuilder json = new StringBuilder();
        json.append("{\"data\":{\"").append(selection.rootResponseKey()).append("\":{");
        boolean firstConnectionField = true;
        GraphqlSelection.RootConnectionSelection connectionSelection = selection.rootConnectionSelection();
        if (connectionSelection.edges()) {
            json.append("\"edges\":[");
            for (int i = 0; i < articles.size(); i++) {
                DemoBlogFixtureStore.ArticleRow article = articles.get(i);
                if (i > 0) {
                    json.append(',');
                }
                appendArticleEdge(json, selection, article, store);
            }
            json.append(']');
            firstConnectionField = false;
        }
        if (connectionSelection.totalCount()) {
            if (!firstConnectionField) {
                json.append(',');
            }
            json.append("\"totalCount\":").append(totalCount);
            firstConnectionField = false;
        }
        if (connectionSelection.pageInfo()) {
            if (!firstConnectionField) {
                json.append(',');
            }
            json.append("\"pageInfo\":");
            appendPageInfo(json, connectionSelection.pageInfoFields(), articles, selection, store, hasNextPage, hasPreviousPage);
        }
        json.append("}}}");
        return json.toString();
    }

    private static void appendArticle(
            StringBuilder json,
            GraphqlSelection selection,
            DemoBlogFixtureStore.ArticleRow article,
            DemoBlogFixtureStore.UserRow author,
            List<DemoBlogFixtureStore.CommentRow> comments,
            DemoBlogFixtureStore store
    ) {
        boolean first = true;
        for (GraphqlSelection.FieldSelection field : selection.fields()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                appendArticleField(json, field, article);
            } else if (field.name().equals("author")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendAuthor(json, field.selections(), author);
            } else if (field.name().equals("comments")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendComments(json, field, comments, store);
            } else {
                throw new GraphqlException("unsupported Article relation '" + field.name() + "'");
            }
        }
    }

    private static void appendArticleEdge(
            StringBuilder json,
            GraphqlSelection selection,
            DemoBlogFixtureStore.ArticleRow article,
            DemoBlogFixtureStore store
    ) {
        json.append('{');
        boolean first = true;
        GraphqlSelection.RootConnectionSelection connectionSelection = selection.rootConnectionSelection();
        if (connectionSelection.edgeCursor()) {
            json.append("\"cursor\":\"").append(GraphqlJsonWriter.escape(articleCursor(selection, article, store))).append('"');
            first = false;
        }
        if (connectionSelection.edgeNode()) {
            if (!first) {
                json.append(',');
            }
            DemoBlogFixtureStore.UserRow author = selection.includesRelation("author")
                    ? store.user(article.authorId())
                    : null;
            List<DemoBlogFixtureStore.CommentRow> comments = selection.includesRelation("comments")
                    ? store.commentsForArticle(article.id())
                    : List.of();
            json.append("\"node\":{");
            appendArticle(json, selection, article, author, comments, store);
            json.append('}');
        }
        json.append('}');
    }

    private static void appendPageInfo(
            StringBuilder json,
            List<String> fields,
            List<DemoBlogFixtureStore.ArticleRow> articles,
            GraphqlSelection selection,
            DemoBlogFixtureStore store,
            boolean hasNextPage,
            boolean hasPreviousPage
    ) {
        json.append('{');
        Iterator<String> iterator = fields.iterator();
        while (iterator.hasNext()) {
            String field = iterator.next();
            switch (field) {
                case "hasNextPage" -> json.append("\"hasNextPage\":").append(hasNextPage);
                case "hasPreviousPage" -> json.append("\"hasPreviousPage\":").append(hasPreviousPage);
                case "startCursor" -> appendNullableCursor(json, "startCursor", articles, selection, store, true);
                case "endCursor" -> appendNullableCursor(json, "endCursor", articles, selection, store, false);
                default -> throw new GraphqlException("unsupported pageInfo field '" + field + "'");
            }
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append('}');
    }

    private static void appendNullableCursor(
            StringBuilder json,
            String fieldName,
            List<DemoBlogFixtureStore.ArticleRow> articles,
            GraphqlSelection selection,
            DemoBlogFixtureStore store,
            boolean first
    ) {
        json.append('"').append(fieldName).append("\":");
        if (articles.isEmpty()) {
            json.append("null");
            return;
        }
        DemoBlogFixtureStore.ArticleRow article = first ? articles.getFirst() : articles.getLast();
        json.append('"').append(GraphqlJsonWriter.escape(articleCursor(selection, article, store))).append('"');
    }

    private static String articleCursor(
            GraphqlSelection selection,
            DemoBlogFixtureStore.ArticleRow article,
            DemoBlogFixtureStore store
    ) {
        if (selection.rootOrderBy().isEmpty() == false) {
            GraphqlSelection.RootOrder order = selection.rootOrderBy().getFirst();
            return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                    order,
                    articleOrderValue(order, article, store),
                    Long.toString(article.id())
            ));
        }
        return GraphqlCursorCodec.encode(GraphqlCursorCodec.payload(
                selection.rootCursorOrdering(),
                Long.toString(article.id()),
                Long.toString(article.id())
        ));
    }

    private static String articleOrderValue(
            GraphqlSelection.RootOrder order,
            DemoBlogFixtureStore.ArticleRow article,
            DemoBlogFixtureStore store
    ) {
        return switch (order.columnName()) {
            case "id" -> Long.toString(article.id());
            case "author_id" -> order.sortPath().equals("author.name")
                    ? authorName(article, store)
                    : Long.toString(article.authorId());
            case "title" -> article.title();
            case "titleLength" -> Integer.toString(article.title().length());
            default -> throw new GraphqlException("unsupported generated cursor order path '" + order.name() + "'");
        };
    }

    private static String authorName(DemoBlogFixtureStore.ArticleRow article, DemoBlogFixtureStore store) {
        DemoBlogFixtureStore.UserRow author = store.user(article.authorId());
        return author == null ? "" : author.name();
    }

    private static String commentCursor(DemoBlogFixtureStore.CommentRow comment) {
        return GraphqlCursorCodec.encode(new GraphqlCursorCodec.CursorPayload(
                "id",
                "id",
                GraphqlRootField.RootCursorDirection.ASC,
                Long.toString(comment.id()),
                "id",
                Long.toString(comment.id())
        ));
    }

    private static void appendArticleField(
            StringBuilder json,
            GraphqlSelection.FieldSelection field,
            DemoBlogFixtureStore.ArticleRow article
    ) {
        switch (field.name()) {
            case "__typename" -> appendStringField(json, field.responseKey(), "Article");
            case "id" -> json.append('"').append(field.responseKey()).append("\":").append(article.id());
            case "title" -> appendStringField(json, field.responseKey(), article.title());
            case "titleLength" -> json.append('"').append(field.responseKey()).append("\":").append(article.title().length());
            default -> throw new GraphqlException("unsupported Article field '" + field.name() + "'");
        }
    }

    private static void appendAuthor(
            StringBuilder json,
            List<GraphqlSelection.FieldSelection> fields,
            DemoBlogFixtureStore.UserRow author
    ) {
        if (author == null) {
            json.append("null");
            return;
        }
        json.append('{');
        Iterator<GraphqlSelection.FieldSelection> iterator = fields.iterator();
        while (iterator.hasNext()) {
            GraphqlSelection.FieldSelection field = iterator.next();
            switch (field.name()) {
                case "__typename" -> appendStringField(json, field.responseKey(), "User");
                case "id" -> json.append('"').append(field.responseKey()).append("\":").append(author.id());
                case "name" -> appendStringField(json, field.responseKey(), author.name());
                case "email" -> appendStringField(json, field.responseKey(), author.email());
                default -> throw new GraphqlException("unsupported User field '" + field.name() + "'");
            }
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append('}');
    }

    private static void appendComments(
            StringBuilder json,
            GraphqlSelection.FieldSelection selection,
            List<DemoBlogFixtureStore.CommentRow> comments,
            DemoBlogFixtureStore store
    ) {
        if (selection.relationConnectionSelection().selected()) {
            appendCommentsConnection(json, selection, comments, store);
            return;
        }
        json.append('[');
        for (int i = 0; i < comments.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendComment(json, selection, comments.get(i), store);
        }
        json.append(']');
    }

    private static void appendCommentsConnection(
            StringBuilder json,
            GraphqlSelection.FieldSelection selection,
            List<DemoBlogFixtureStore.CommentRow> comments,
            DemoBlogFixtureStore store
    ) {
        CommentConnectionPage page = commentConnectionPage(comments, selection);
        GraphqlSelection.RelationConnectionSelection connectionSelection = selection.relationConnectionSelection();
        json.append('{');
        boolean firstConnectionField = true;
        if (connectionSelection.edges()) {
            json.append("\"edges\":[");
            for (int i = 0; i < page.comments().size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                appendCommentEdge(json, selection, page.comments().get(i), store);
            }
            json.append(']');
            firstConnectionField = false;
        }
        if (connectionSelection.totalCount()) {
            if (!firstConnectionField) {
                json.append(',');
            }
            json.append("\"totalCount\":").append(comments.size());
            firstConnectionField = false;
        }
        if (connectionSelection.pageInfo()) {
            if (!firstConnectionField) {
                json.append(',');
            }
            json.append("\"pageInfo\":");
            appendRelationPageInfo(
                    json,
                    connectionSelection.pageInfoFields(),
                    page.comments(),
                    page.hasNextPage(),
                    page.hasPreviousPage()
            );
        }
        json.append('}');
    }

    private static void appendCommentEdge(
            StringBuilder json,
            GraphqlSelection.FieldSelection selection,
            DemoBlogFixtureStore.CommentRow comment,
            DemoBlogFixtureStore store
    ) {
        GraphqlSelection.RelationConnectionSelection connectionSelection = selection.relationConnectionSelection();
        json.append('{');
        boolean first = true;
        if (connectionSelection.edgeCursor()) {
            json.append("\"cursor\":\"").append(GraphqlJsonWriter.escape(commentCursor(comment))).append('"');
            first = false;
        }
        if (connectionSelection.edgeNode()) {
            if (!first) {
                json.append(',');
            }
            json.append("\"node\":");
            appendComment(json, selection, comment, store);
        }
        json.append('}');
    }

    private static void appendRelationPageInfo(
            StringBuilder json,
            List<String> fields,
            List<DemoBlogFixtureStore.CommentRow> comments,
            boolean hasNextPage,
            boolean hasPreviousPage
    ) {
        json.append('{');
        Iterator<String> iterator = fields.iterator();
        while (iterator.hasNext()) {
            String field = iterator.next();
            switch (field) {
                case "hasNextPage" -> json.append("\"hasNextPage\":").append(hasNextPage);
                case "hasPreviousPage" -> json.append("\"hasPreviousPage\":").append(hasPreviousPage);
                case "startCursor" -> appendNullableCommentCursor(json, "startCursor", comments, true);
                case "endCursor" -> appendNullableCommentCursor(json, "endCursor", comments, false);
                default -> throw new GraphqlException("unsupported pageInfo field '" + field + "'");
            }
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append('}');
    }

    private static void appendNullableCommentCursor(
            StringBuilder json,
            String fieldName,
            List<DemoBlogFixtureStore.CommentRow> comments,
            boolean first
    ) {
        json.append('"').append(fieldName).append("\":");
        if (comments.isEmpty()) {
            json.append("null");
            return;
        }
        DemoBlogFixtureStore.CommentRow comment = first ? comments.getFirst() : comments.getLast();
        json.append('"').append(GraphqlJsonWriter.escape(commentCursor(comment))).append('"');
    }

    private static CommentConnectionPage commentConnectionPage(
            List<DemoBlogFixtureStore.CommentRow> comments,
            GraphqlSelection.FieldSelection selection
    ) {
        long afterId = relationCursorId(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_AFTER, Long.MIN_VALUE);
        long beforeId = relationCursorId(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_BEFORE, Long.MAX_VALUE);
        List<DemoBlogFixtureStore.CommentRow> window = new java.util.ArrayList<>();
        for (DemoBlogFixtureStore.CommentRow comment : comments) {
            if (comment.id() > afterId && comment.id() < beforeId) {
                window.add(comment);
            }
        }
        boolean hasPreviousPage = relationCursor(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_AFTER) != null;
        boolean hasNextPage = relationCursor(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_BEFORE) != null;
        int requested = relationPageSize(selection);
        if (relationArgument(selection, GraphqlRelationArgumentDescriptor.RelationArgumentKind.RELAY_LAST) != null) {
            int from = Math.max(0, window.size() - requested);
            hasPreviousPage = hasPreviousPage || from > 0;
            return new CommentConnectionPage(
                    List.copyOf(window.subList(from, window.size())),
                    hasNextPage,
                    hasPreviousPage
            );
        }
        int to = Math.min(window.size(), requested);
        hasNextPage = hasNextPage || window.size() > requested;
        return new CommentConnectionPage(List.copyOf(window.subList(0, to)), hasNextPage, hasPreviousPage);
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

    private static void appendComment(
            StringBuilder json,
            GraphqlSelection.FieldSelection selection,
            DemoBlogFixtureStore.CommentRow comment,
            DemoBlogFixtureStore store
    ) {
        json.append('{');
        boolean first = true;
        Iterator<GraphqlSelection.FieldSelection> iterator = selection.selections().iterator();
        while (iterator.hasNext()) {
            GraphqlSelection.FieldSelection field = iterator.next();
            if (!first) {
                json.append(',');
            }
            first = false;
            if (field.kind() == GraphqlFieldDescriptor.FieldKind.SCALAR) {
                switch (field.name()) {
                    case "__typename" -> appendStringField(json, field.responseKey(), "Comment");
                    case "id" -> json.append('"').append(field.responseKey()).append("\":").append(comment.id());
                    case "body" -> appendStringField(json, field.responseKey(), comment.body());
                    default -> throw new GraphqlException("unsupported Comment field '" + field.name() + "'");
                }
            } else if (field.name().equals("author")) {
                json.append('"').append(field.responseKey()).append("\":");
                appendAuthor(json, field.selections(), store.user(comment.authorId()));
            } else {
                throw new GraphqlException("unsupported Comment relation '" + field.name() + "'");
            }
        }
        json.append('}');
    }

    private static void appendStringField(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"").append(GraphqlJsonWriter.escape(value)).append('"');
    }

    private record CommentConnectionPage(
            List<DemoBlogFixtureStore.CommentRow> comments,
            boolean hasNextPage,
            boolean hasPreviousPage
    ) {
    }
}
