package com.segfault03.ideadb.sql.doc;

import com.segfault03.ideadb.model.DatabaseType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlDocRendererTest {
    private final com.segfault03.ideadb.sql.schema.SqlSchema schema = DocTestSchema.schema(DatabaseType.MYSQL);
    private final String id = schema.connectionId();

    private String render(SqlTarget target) { return SqlDocRenderer.render(target, schema); }

    @Test void aTableCardListsCommentColumnsKeysAndWhatPointsAtIt() {
        String html = render(new SqlTarget.Table("PUBLIC", "customers"));
        assertTrue(html.contains("PUBLIC.customers"), html);
        assertTrue(html.contains("People who buy things"));
        assertTrue(html.contains("<b>id</b>&nbsp;&nbsp;&nbsp;</td><td>INTEGER") && html.contains("PK, not null"), html);
        assertTrue(html.contains("<b>name</b>&nbsp;&nbsp;&nbsp;</td><td>VARCHAR(40)"));
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
        assertTrue(html.contains("<b>total</b>&nbsp;&nbsp;&nbsp;</td><td>DECIMAL(10,2)"), html);
        assertTrue(html.contains("not null"), "one wording for nullability");
        assertFalse(html.contains("Nullable"), html);
    }

    @Test void aTableCardShowsForeignKeysAsLinksToTheTarget() {
        String html = render(new SqlTarget.Table("PUBLIC", "orders"));
        assertTrue(html.contains("Foreign keys"), html);
        assertTrue(html.contains("customer_id</b>&nbsp;&nbsp;&nbsp;</td><td>INTEGER"), html);
        assertTrue(html.contains("PUBLIC.customers.id"), "the label names the target column");
        assertTrue(html.contains(SqlDocLinks.show(id, new SqlTarget.Column("PUBLIC", "customers", "id"))));
        assertFalse(html.contains("Referenced by"), "nothing points at orders");
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
        assertTrue(html.contains("<b>total</b> DECIMAL(10,2)"), html);
        assertTrue(html.contains("Gross, with tax"));
        assertTrue(html.contains("Nullability") && html.contains("not null"), "the wording of the table card's flag");
        assertFalse(html.contains("Nullable"), html);
        String key = render(new SqlTarget.Column("PUBLIC", "orders", "customer_id"));
        assertTrue(key.contains("foreign key") && key.contains("PUBLIC.customers.id"), key);
        String pk = render(new SqlTarget.Column("PUBLIC", "customers", "id"));
        assertTrue(pk.contains("primary key") && pk.contains("auto increment") && pk.contains("Surrogate key"), pk);
    }

    @Test void anAliasCardLinksToItsTable() {
        String html = render(new SqlTarget.Alias("o", "PUBLIC", "orders"));
        assertTrue(html.contains("<b>o</b>") && html.contains("alias for"), html);
        assertTrue(html.contains(SqlDocLinks.show(id, new SqlTarget.Table("PUBLIC", "orders"))));
    }

    @Test void anAliasCardShowsTheSameBodyAsItsTableCard() {
        String alias = render(new SqlTarget.Alias("c", "PUBLIC", "customers"));
        String table = render(new SqlTarget.Table("PUBLIC", "customers"));
        assertTrue(alias.contains("People who buy things"), alias);
        assertTrue(alias.contains("<b>id</b>&nbsp;&nbsp;&nbsp;</td><td>INTEGER") && alias.contains("Referenced by") && alias.contains("Open in Lattice"), alias);
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
