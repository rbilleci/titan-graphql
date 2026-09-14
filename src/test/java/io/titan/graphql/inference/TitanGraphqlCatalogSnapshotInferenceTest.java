package io.titan.graphql.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TitanGraphqlCatalogSnapshotInferenceTest {

    @Test
    void representsDemoBlogCatalogVocabulary() {
        TitanGraphqlCatalogSnapshot snapshot = TitanGraphqlCatalogFixtures.demoBlog();

        assertEquals("demo_blog", snapshot.catalog());
        assertEquals(1, snapshot.schemas().size());

        TitanGraphqlCatalogSnapshot.Schema schema = snapshot.schemas().get(0);
        assertEquals("public", schema.name());
        assertEquals(3, schema.tables().size());

        TitanGraphqlCatalogSnapshot.Table articles = schema.tables().get(0);
        assertEquals("articles", articles.name());
        assertEquals(TitanGraphqlCatalogSnapshot.TableKind.TABLE, articles.kind());
        assertEquals(List.of("id"), articles.primaryKey().columns());
        assertEquals(4, articles.columns().size());
        assertEquals("published", articles.columns().get(3).name());
        assertFalse(articles.columns().get(3).nullable());
        assertEquals("articles_author_id_fkey", articles.foreignKeys().get(0).name());
        assertEquals("users", articles.foreignKeys().get(0).targetTable());
        assertEquals(List.of("published", "id"), articles.indexes().get(2).columns());

        TitanGraphqlCatalogSnapshot.Table users = schema.tables().get(1);
        assertEquals("email", users.columns().get(2).name());
        assertTrue(users.columns().get(2).nullable());
        assertTrue(users.indexes().get(1).unique());

        TitanGraphqlCatalogSnapshot.Table comments = schema.tables().get(2);
        assertEquals(2, comments.foreignKeys().size());
        assertEquals("articles", comments.foreignKeys().get(0).targetTable());
        assertEquals("users", comments.foreignKeys().get(1).targetTable());
    }

    @Test
    void appliesExplicitDefaults() {
        TitanGraphqlCatalogSnapshot snapshot = new TitanGraphqlCatalogSnapshot(null, null);
        TitanGraphqlCatalogSnapshot.Table table = new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "drafts",
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals("", snapshot.catalog());
        assertTrue(snapshot.schemas().isEmpty());
        assertEquals(TitanGraphqlCatalogSnapshot.TableKind.TABLE, table.kind());
        assertEquals("", table.comment());
        assertTrue(table.columns().isEmpty());
        assertTrue(table.foreignKeys().isEmpty());
        assertTrue(table.indexes().isEmpty());
    }

    @Test
    void copiesCollectionsForStableEquality() {
        List<TitanGraphqlCatalogSnapshot.Column> columns = new ArrayList<>();
        columns.add(new TitanGraphqlCatalogSnapshot.Column("id", "integer", false, 1, "identifier"));
        TitanGraphqlCatalogSnapshot.Table first = new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "articles",
                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                "",
                columns,
                new TitanGraphqlCatalogSnapshot.PrimaryKey("articles_pkey", List.of("id")),
                List.of(),
                List.of()
        );

        columns.clear();
        TitanGraphqlCatalogSnapshot.Table second = new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "articles",
                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                "",
                List.of(new TitanGraphqlCatalogSnapshot.Column("id", "integer", false, 1, "identifier")),
                new TitanGraphqlCatalogSnapshot.PrimaryKey("articles_pkey", List.of("id")),
                List.of(),
                List.of()
        );

        assertEquals(second, first);
        assertThrows(UnsupportedOperationException.class, () -> first.columns().add(second.columns().get(0)));
    }

    @Test
    void rejectsMissingRequiredCatalogFields() {
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlCatalogSnapshot.Schema("", "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlCatalogSnapshot.Table(
                "public",
                "",
                TitanGraphqlCatalogSnapshot.TableKind.TABLE,
                "",
                List.of(),
                null,
                List.of(),
                List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlCatalogSnapshot.Column(
                "id",
                "",
                false,
                1,
                ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlCatalogSnapshot.PrimaryKey(
                "articles_pkey",
                List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlCatalogSnapshot.ForeignKey(
                "articles_author_id_fkey",
                List.of("author_id"),
                "",
                "users",
                List.of("id"),
                ""
        ));
        assertThrows(IllegalArgumentException.class, () -> new TitanGraphqlCatalogSnapshot.Index(
                "articles_id_idx",
                List.of(""),
                true,
                ""
        ));
    }
}
