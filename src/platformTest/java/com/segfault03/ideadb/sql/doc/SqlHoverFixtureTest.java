package com.segfault03.ideadb.sql.doc;

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.LinkResolveResult;
import com.intellij.testFramework.PlatformTestUtil;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;

import java.util.List;

/** What the documentation popup is given when the pointer is over a name, over an H2 schema with comments and a foreign key. */
public class SqlHoverFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))",
                "COMMENT ON TABLE AUTHORS IS 'People who write'",
                "COMMENT ON COLUMN BOOKS.TITLE IS 'As printed on the cover'"};
    }

    /** The targets for the pointer at the caret marker. */
    private List<? extends DocumentationTarget> hover(String text) {
        myFixture.configureByText("query.sql", text);
        int offset = myFixture.getCaretOffset();
        return IdeDocumentationTargetProvider.getInstance(getProject()).documentationTargets(myFixture.getEditor(), myFixture.getFile(), offset);
    }

    private String card(String text) {
        var targets = hover(text);
        assertEquals(targets.toString(), 1, targets.size());
        String html = ((SqlDocumentationTarget) targets.get(0)).html();
        assertNotNull(html);
        return html;
    }

    public void testATableShowsItsCommentColumnsAndKeys() {
        loadSchema();
        String html = card("select * from <caret>authors");
        assertTrue(html, html.contains("PUBLIC.AUTHORS") && html.contains("People who write"));
        assertTrue(html.contains("<b>ID</b>&nbsp;&nbsp;&nbsp;</td><td>INTEGER") && html.contains("Referenced by") && html.contains("BOOKS.AUTHOR_ID"));
        assertFalse("the source is the popup's location bar, not a line of the card", html.contains("fixture H2"));
    }

    public void testTheLocationBarNamesTheConnectionAndTheDatabaseTypeWithItsIcon() {
        loadSchema();
        var target = (SqlDocumentationTarget) hover("select * from <caret>authors").get(0);
        var presentation = target.computePresentation();
        assertEquals("AUTHORS", presentation.getPresentableText());
        assertEquals("fixture H2 \u00b7 H2", presentation.getLocationText());
        assertNotNull("the location carries the database type's icon", presentation.getLocationIcon());
    }

    public void testAColumnThroughAnAliasShowsItsCommentAndForeignKey() {
        loadSchema();
        assertTrue(card("select b.<caret>title from books b").contains("As printed on the cover"));
        String key = card("select b.<caret>author_id from books b");
        assertTrue(key, key.contains("foreign key") && key.contains("AUTHORS.ID"));
    }

    public void testAnAliasLeadsToItsTable() {
        loadSchema();
        String html = card("select * from books <caret>b");
        assertTrue(html, html.contains("alias for") && html.contains("PUBLIC.BOOKS"));
        assertTrue("the alias card has the table card's columns and keys", html.contains("<b>TITLE</b>&nbsp;&nbsp;&nbsp;</td><td>CHARACTER VARYING(60)") && html.contains("Foreign keys"));
    }

    public void testNothingIsShownForKeywordsUnknownNamesOrWhitespace() {
        loadSchema();
        assertTrue(hover("<caret>select * from books").isEmpty());
        assertTrue(hover("select * from <caret>nothing").isEmpty());
        assertTrue(hover("select * from books where<caret> 1 = 1").isEmpty());
    }

    public void testLinksInACardLeadToTheOtherCardAndForeignLinksAreIgnored() {
        loadSchema();
        var target = hover("select * from <caret>books").get(0);
        var handler = new LatticeSqlDocumentationLinkHandler();
        var toAuthors = SqlDocLinks.show(h2.getId(), new SqlTarget.Column("PUBLIC", "AUTHORS", "ID"));
        LinkResolveResult resolved = handler.resolveLink(target, toAuthors);
        assertNotNull(resolved);
        assertNull(handler.resolveLink(target, "https://example.com"));
        assertNull("A link to something that is gone is not followed", handler.resolveLink(target, SqlDocLinks.show(h2.getId(), new SqlTarget.Table("PUBLIC", "GONE"))));
    }

    public void testWithoutACachedSchemaNothingIsShownAndTheReadStarts() {
        assertTrue(hover("select * from <caret>books").isEmpty());
        PlatformTestUtil.waitWithEventsDispatching("Hover should have started reading the schema", () -> {
            var schema = SqlSchemaService.getInstance().schema(h2);
            return schema != null && schema.isComplete(null);
        }, 30);
        assertEquals(1, hover("select * from <caret>books").size());
    }

    public void testHoverCanBeTurnedOff() {
        loadSchema();
        SqlEditorSettings.getInstance().setHover(false);
        assertTrue(hover("select * from <caret>books").isEmpty());
    }
}
