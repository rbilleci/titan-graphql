package io.titan.graphql.demo.blog;

import io.titan.graphql.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class DemoBlogFixtureStore {

    private final Map<Long, UserRow> users = Map.of(
            10L, new UserRow(10L, "Ada Lovelace", "ada@example.test", "author"),
            11L, new UserRow(11L, "Grace Hopper", "grace@example.test", "author")
    );
    private final Map<Long, ArticleRow> articles = Map.of(
            1L, new ArticleRow(1L, 10L, "Titan GraphQL proof", "First proof body", true),
            2L, new ArticleRow(2L, 11L, "Stored functions as APIs", "Second proof body", false)
    );
    private final Map<Long, CommentRow> comments = Map.of(
            100L, new CommentRow(100L, 1L, 11L, "First comment"),
            101L, new CommentRow(101L, 1L, 10L, "Second comment"),
            102L, new CommentRow(102L, 2L, 10L, "API comment")
    );

    ArticleRow article(long id) {
        return articles.get(id);
    }

    List<ArticleRow> articles(int limit) {
        return articlesByAuthor(-1L, limit);
    }

    List<ArticleRow> articlesByAuthor(long authorId) {
        return articlesByAuthor(authorId, Integer.MAX_VALUE);
    }

    List<ArticleRow> articlesByAuthor(long authorId, int limit) {
        List<ArticleRow> rows = new ArrayList<>(articles.values());
        rows.sort(Comparator.comparingLong(ArticleRow::id));
        if (authorId >= 0L) {
            rows.removeIf(article -> article.authorId() != authorId);
        }
        if (limit >= rows.size()) {
            return List.copyOf(rows);
        }
        return List.copyOf(rows.subList(0, limit));
    }

    UserRow user(long id) {
        return users.get(id);
    }

    List<CommentRow> commentsForArticle(long articleId) {
        List<CommentRow> rows = new ArrayList<>();
        for (CommentRow comment : comments.values()) {
            if (comment.articleId() == articleId) {
                rows.add(comment);
            }
        }
        rows.sort(Comparator.comparingLong(CommentRow::id));
        return List.copyOf(rows);
    }

    record UserRow(long id, String name, String email, String role) {
    }

    record ArticleRow(long id, long authorId, String title, String body, boolean published) {
    }

    record CommentRow(long id, long articleId, long authorId, String body) {
    }
}
