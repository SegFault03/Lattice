package com.segfault03.ideadb.sql.doc;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlDocRendererTest {
    private final SqlSchema schema = DocTestSchema.schema(DatabaseType.MYSQL);
    private final String id = schema.connectionId();

    private String render(SqlTarget target) { return SqlDocRenderer.render(target, schema, SqlDocPalette.LIGHT); }

    /** The card's text without its markup, for what is said rather than how it is colored. */
    private static String plain(String html) { return html.replace("</td>", " ").replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&rarr;", "->").replace("&larr;", "<-"); }

    @Test void aTableCardListsCommentColumnsKeysAndWhatPointsAtIt() {
        String html = render(new SqlTarget.Table("PUBLIC", "customers"));
        assertTrue(plain(html).contains("PUBLIC.customers"), html);
        assertTrue(html.contains("People who buy things"));
        assertTrue(html.contains(">id</span></b></td>") && html.contains("INTEGER</span></td>") && plain(html).contains("PK, not null"), html);
        assertTrue(html.contains("<b>name</b></td>") && html.contains("VARCHAR(40)</span></td>"));
        assertTrue(html.contains("Referenced by"));
        assertTrue(html.contains(SqlDocLinks.show(id, new SqlTarget.Column("PUBLIC", "orders", "customer_id")).replace("&", "&amp;")), "links to orders.customer_id");
        assertTrue(html.contains("Open in Lattice"));
        assertFalse(html.contains("MYSQL"), "where the card came from is the popup's location bar, not a line of the card");
        assertFalse(html.contains("Primary key"), "the key is flagged on its column, not stated twice");
    }

    @Test void theColumnsAreAnAlignedTableOfNamesAndTypesWithFlags() {
        String html = render(new SqlTarget.Table("PUBLIC", "orders"));
        assertTrue(html.contains("<table"), html);
        String table = html.substring(html.indexOf("<table cellspacing"), html.indexOf("</table>", html.indexOf("<table cellspacing")));
        assertEquals(3, table.split("<tr>").length - 1, "one row per column of orders");
        assertEquals(3, table.split("</tr>", -1).length - 1, "every row is closed");
        assertTrue(table.contains("<b>total</b></td>") && table.contains("DECIMAL(10,2)</span></td>"), html);
        assertTrue(table.contains("not&nbsp;null"), "one wording for nullability, which never breaks");
        assertEquals(6, table.split("padding: 0 8px 0 0", -1).length - 1, "name and type cells of each row are padded");
        assertEquals(9, table.split("nowrap=\"nowrap\"", -1).length - 1, "no cell of the three columns wraps");
        assertTrue(plain(table).contains("PK, not null"), "the flags are a column of their own");
        assertTrue(html.endsWith(DocumentationMarkup.SECTIONS_END), "no trailing block above the location bar");
        assertFalse(html.contains(DocumentationMarkup.CONTENT_START + DocumentationMarkup.CONTENT_END), "no empty content block");
        assertFalse(html.contains("Nullable"), html);
    }

    @Test void aTableCardShowsForeignKeysAsLinksToTheTarget() {
        String html = render(new SqlTarget.Table("PUBLIC", "orders"));
        assertTrue(html.contains("Foreign keys"), html);
        assertTrue(html.contains("customer_id</span></b></td>") && html.contains("INTEGER</span></td>"), html);
        assertTrue(plain(html).contains("customer_id -> PUBLIC.customers.id"), "the label names the target column: " + plain(html));
        assertTrue(html.contains(SqlDocLinks.show(id, new SqlTarget.Column("PUBLIC", "customers", "id"))));
        assertFalse(html.contains("Referenced by"), "nothing points at orders");
    }

    @Test void referencedByShowsWhichColumnOfThisTableIsPointedAt() {
        String html = render(new SqlTarget.Table("PUBLIC", "customers"));
        assertTrue(plain(html).contains("id <- orders.customer_id"), plain(html));
        assertTrue(html.contains("&larr;") && !html.contains("&rarr;"), "the arrow points from the referencing column to the one it references");
        assertTrue(html.contains(SqlDocLinks.show(id, new SqlTarget.Column("PUBLIC", "orders", "customer_id")).replace("&", "&amp;")), "the referencing column stays a link");
    }

    @Test void severalColumnsReferencingOneColumnShareARowSeparatedByCommas() {
        var ref = new ColumnReference("PUBLIC", "customers", "id");
        var customers = DocTestSchema.table("customers", "TABLE", null, true,
                DocTestSchema.column("id", "INTEGER", false, true, true, null, null), DocTestSchema.column("email", "VARCHAR(40)", true, false, false, null, null));
        var orders = DocTestSchema.table("orders", "TABLE", null, true, DocTestSchema.column("customer_id", "INTEGER", false, false, false, null, ref));
        var notes = DocTestSchema.table("notes", "TABLE", null, true, DocTestSchema.column("author_id", "INTEGER", false, false, false, null, ref));
        var reviews = DocTestSchema.table("reviews", "TABLE", null, true, DocTestSchema.column("customer_id", "INTEGER", false, false, false, null, ref));
        var snapshot = new ExplorerMetadataSnapshot(List.of(new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(customers, orders, notes, reviews), true)), List.of(), 1);
        var many = SqlSchema.of(new ConnectionConfig(DatabaseType.MYSQL, "doc test"), snapshot, "PUBLIC", 1);
        String html = SqlDocRenderer.render(new SqlTarget.Table("PUBLIC", "customers"), many, SqlDocPalette.LIGHT);
        assertTrue(plain(html).contains("id <- orders.customer_id, notes.author_id, reviews.customer_id"), plain(html));
        assertEquals(1, html.split("&larr;", -1).length - 1, "one row for the one column that is pointed at");
        int row = html.indexOf("&larr;");
        String references = html.substring(row, html.indexOf("</tr>", row));
        assertFalse(references.contains("nowrap"), "the list of referencing columns wraps rather than overflowing");
        assertFalse(references.contains("<br>"), "no line of its own for each");
    }

    @Test void keyColumnsAreColoredAndEveryTypeByItsKind() {
        var palette = SqlDocPalette.DARK;
        String customers = SqlDocRenderer.render(new SqlTarget.Table("PUBLIC", "customers"), schema, palette);
        assertTrue(customers.contains(SqlDocPalette.paint(palette.key(), "id")), "a primary key column's name is in the key color");
        assertTrue(customers.contains(SqlDocPalette.paint(palette.key(), "<b>PK</b>")), customers);
        String orders = SqlDocRenderer.render(new SqlTarget.Table("PUBLIC", "orders"), schema, palette);
        assertTrue(orders.contains(SqlDocPalette.paint(palette.foreignKey(), "customer_id")), "a foreign key column's name is in the foreign key color");
        assertTrue(orders.contains(SqlDocPalette.paint(palette.foreignKey(), "<b>FK</b>")), orders);
        assertTrue(orders.contains(SqlDocPalette.paint(palette.numeric(), "INTEGER")), orders);
        assertNotEquals(SqlDocRenderer.render(new SqlTarget.Table("PUBLIC", "orders"), schema, SqlDocPalette.LIGHT), orders, "the palette follows the scheme");
    }

    @Test void typesAreColoredByWhatTheyHold() {
        var palette = SqlDocPalette.LIGHT;
        assertEquals(palette.text(), palette.typeColor(new ColumnMetadata("a", "VARCHAR", Types.VARCHAR, 10, 0, true, false, false, null)));
        assertEquals(palette.numeric(), palette.typeColor(new ColumnMetadata("a", "DECIMAL", Types.DECIMAL, 10, 2, true, false, false, null)));
        assertEquals(palette.temporal(), palette.typeColor(new ColumnMetadata("a", "TIMESTAMP", Types.TIMESTAMP, 0, 0, true, false, false, null)));
        assertEquals(palette.bool(), palette.typeColor(new ColumnMetadata("a", "BIT", Types.BIT, 1, 0, true, false, false, null)));
        assertEquals(palette.binary(), palette.typeColor(new ColumnMetadata("a", "BLOB", Types.BLOB, 0, 0, true, false, false, null)));
        assertEquals(palette.other(), palette.typeColor(new ColumnMetadata("a", "JSON", Types.LONGVARCHAR, 0, 0, true, false, false, null)));
    }

    @Test void aViewIsLabelledAsOneAndItsCommentIsEscaped() {
        String html = render(new SqlTarget.Table("PUBLIC", "order_summary"));
        assertTrue(html.contains("view"));
        assertTrue(html.contains("&lt;b&gt;not&lt;/b&gt; a table"), html);
        assertFalse(html.contains("<b>not</b>"));
    }

    @Test void aTableWhoseColumnsWereNotReadSaysSo() {
        String html = render(new SqlTarget.Table("PUBLIC", "unread"));
        assertTrue(html.contains("not read yet"), html);
    }

    @Test void aColumnCardHasTypeCommentNullabilityAndKeys() {
        String html = render(new SqlTarget.Column("PUBLIC", "orders", "total"));
        assertTrue(html.contains("<b>total</b> ") && plain(html).contains("total DECIMAL(10,2)"), html);
        assertTrue(html.contains("Gross, with tax"));
        assertTrue(html.contains("Nullability") && html.contains("not null"), "the wording of the table card's flag");
        assertFalse(html.contains("Nullable"), html);
        String key = render(new SqlTarget.Column("PUBLIC", "orders", "customer_id"));
        assertTrue(plain(key).contains("foreign key -> PUBLIC.customers.id"), key);
        String pk = render(new SqlTarget.Column("PUBLIC", "customers", "id"));
        assertTrue(plain(pk).contains("primary key") && pk.contains("auto increment") && pk.contains("Surrogate key"), pk);
    }

    @Test void anAliasCardLinksToItsTable() {
        String html = render(new SqlTarget.Alias("o", "PUBLIC", "orders"));
        assertTrue(html.contains(">o</span></b>") && html.contains("alias for"), html);
        assertTrue(html.contains(SqlDocLinks.show(id, new SqlTarget.Table("PUBLIC", "orders"))));
    }

    @Test void anAliasCardShowsTheSameBodyAsItsTableCard() {
        String alias = render(new SqlTarget.Alias("c", "PUBLIC", "customers"));
        String table = render(new SqlTarget.Table("PUBLIC", "customers"));
        assertTrue(alias.contains("People who buy things"), alias);
        assertTrue(alias.contains(">id</span></b></td>") && alias.contains("Referenced by") && alias.contains("Open in Lattice"), alias);
        assertEquals(table.substring(table.indexOf("<table cellspacing")), alias.substring(alias.indexOf("<table cellspacing")), "the columns and keys are the table card's");
    }

    @Test void aSchemaCardListsItsTables() {
        String html = render(new SqlTarget.Schema("audit"));
        assertTrue(html.contains("schema") && html.contains(">log</a>"), html);
        assertTrue(render(new SqlTarget.Schema("empty")).contains("not read yet"));
    }

    @Test void namesThatNoLongerExistRenderNothing() {
        assertNull(render(new SqlTarget.Table("PUBLIC", "gone")));
        assertNull(render(new SqlTarget.Column("PUBLIC", "orders", "gone")));
        assertNull(render(new SqlTarget.Schema("gone")));
    }

    @Test void linksRoundTripNamesWithSlashesSpacesAndUnicode() {
        var tricky = new SqlTarget.Column("my/schema", "Order Items", "naïve col");
        var parsed = SqlDocLinks.parse(SqlDocLinks.show("conn-1", tricky));
        assertNotNull(parsed);
        assertEquals("column", parsed.kind());
        assertEquals("conn-1", parsed.connectionId());
        assertEquals(tricky, parsed.target());
        var open = SqlDocLinks.parse(SqlDocLinks.open("c", "S", "T"));
        assertEquals("open", open.kind());
        assertEquals(new SqlTarget.Table("S", "T"), open.target());
    }

    @Test void foreignAndMalformedLinksAreIgnored() {
        assertNull(SqlDocLinks.parse("https://example.com"));
        assertNull(SqlDocLinks.parse("lattice-sql:table/onlyconnection"));
        assertNull(SqlDocLinks.parse("lattice-sql:bogus/c/a/b"));
        assertNull(SqlDocLinks.parse("lattice-sql:column/c/a/b"));
    }
}
