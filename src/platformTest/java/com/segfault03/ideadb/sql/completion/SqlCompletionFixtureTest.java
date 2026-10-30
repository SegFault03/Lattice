package com.segfault03.ideadb.sql.completion;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.ui.Icons;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Completion in a real editor over an H2 schema read through the schema service. */
public class SqlCompletionFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))"};
    }

    private List<String> lookups(String text) {
        myFixture.configureByText("query.sql", text);
        LookupElement[] items = myFixture.completeBasic();
        return items == null ? List.of() : Arrays.stream(items).map(LookupElement::getLookupString).toList();
    }

    public void testTableSlotOffersTheSchemasTables() {
        loadSchema();
        var items = lookups("select * from <caret>");
        assertTrue(items.toString(), items.containsAll(List.of("AUTHORS", "BOOKS")));
        assertFalse("Keywords do not belong in a bare table slot", items.contains("SELECT") || items.contains("select"));
    }

    public void testAliasQualifiedColumnsAreOffered() {
        loadSchema();
        var items = lookups("select b.<caret> from books b");
        assertEquals(List.of("AUTHOR_ID", "ID", "TITLE"), items);
    }

    public void testTheForeignKeyJoinConditionRanksFirst() {
        loadSchema();
        var items = lookups("select * from books b join authors a on <caret>");
        assertEquals(items.toString(), "b.AUTHOR_ID = a.ID", items.get(0));
    }

    public void testAJoinSlotOffersTheForeignKeyJoinsFirst() {
        loadSchema();
        var items = lookups("select * from books b join <caret>");
        assertEquals(items.toString(), "AUTHORS A ON A.ID = b.AUTHOR_ID", items.get(0));
        var reverse = lookups("select * from authors a join <caret>");
        assertEquals(reverse.toString(), "BOOKS B ON B.AUTHOR_ID = a.ID", reverse.get(0));
        assertTrue(items.contains("BOOKS") && items.contains("AUTHORS"));
    }

    public void testChoosingAJoinWritesTableAliasAndCondition() {
        loadSchema();
        myFixture.configureByText("query.sql", "select * from books b join <caret>");
        var items = myFixture.completeBasic();
        myFixture.getLookup().setCurrentItem(Arrays.stream(items).filter(e -> e.getLookupString().startsWith("AUTHORS A ON")).findFirst().orElseThrow());
        myFixture.type('\n');
        myFixture.checkResult("select * from books b join AUTHORS A ON A.ID = b.AUTHOR_ID");
    }

    public void testColumnsShowTheirKeyRoleAndTablesAndViewsHaveDifferentIcons() {
        loadSchema();
        myFixture.configureByText("query.sql", "select b.<caret> from books b");
        var items = myFixture.completeBasic();
        var presentations = new HashMap<String, LookupElementPresentation>();
        for (LookupElement item : items) {
            var presentation = new LookupElementPresentation();
            item.renderElement(presentation);
            presentations.put(item.getLookupString(), presentation);
        }
        assertEquals("PK INTEGER", presentations.get("ID").getTypeText());
        assertEquals("FK INTEGER", presentations.get("AUTHOR_ID").getTypeText());
        assertEquals("CHARACTER VARYING(60)", presentations.get("TITLE").getTypeText());
        assertNull("the qualifier already names the table", presentations.get("TITLE").getTailText());
        assertNotNull(presentations.get("TITLE").getIcon());
        assertNotSame(presentations.get("ID").getIcon(), presentations.get("TITLE").getIcon());
        assertNotSame(presentations.get("AUTHOR_ID").getIcon(), presentations.get("TITLE").getIcon());
        assertNotSame(Icons.TABLE, Icons.VIEW);
    }

    public void testTheLowercaseTextTheUserTypedStillMatchesUppercaseNames() {
        loadSchema();
        myFixture.configureByText("query.sql", "select * from auth<caret>");
        myFixture.completeBasic();
        myFixture.checkResult("select * from AUTHORS");
    }

    public void testKeywordsAndFunctionsFollowTheDialectAndTheTypedCase() {
        loadSchema();
        var items = lookups("select * from books <caret>");
        assertTrue(items.toString(), items.containsAll(List.of("where", "limit", "fetch first", "order by", "left join")));
        assertFalse(items.contains("LIMIT"));

        var upper = lookups("SELECT * FROM books <caret>");
        assertTrue(upper.toString(), upper.contains("WHERE"));
    }

    public void testSelectingAFunctionAddsParentheses() {
        loadSchema();
        myFixture.configureByText("query.sql", "select coun<caret> from books");
        LookupElement[] items = myFixture.completeBasic();
        if (items != null) myFixture.getLookup().setCurrentItem(Arrays.stream(items).filter(e -> e.getLookupString().equals("count")).findFirst().orElseThrow());
        if (items != null) myFixture.type('\n');
        myFixture.checkResult("select count(<caret>) from books");
    }

    public void testInsertColumnListSkipsColumnsAlreadyNamed() {
        loadSchema();
        var items = lookups("insert into books (id, <caret>)");
        assertEquals(items.toString(), List.of("AUTHOR_ID", "TITLE"), items);
    }

    public void testCommentsAndStringsGetNoSchemaSuggestions() {
        loadSchema();
        assertFalse(lookups("select 1 -- <caret>").contains("AUTHORS"));
        assertFalse(lookups("select 'x<caret>'").contains("AUTHORS"));
    }

    public void testNothingIsOfferedOnceCompletionIsTurnedOff() {
        loadSchema();
        SqlEditorSettings.getInstance().setCompletion(false);
        assertFalse(lookups("select * from <caret>").contains("AUTHORS"));
    }

    public void testWithoutACachedSchemaWordsAreOfferedAndTheReadStarts() {
        var items = lookups("select * from books <caret>");
        assertTrue(items.toString(), items.contains("where"));
        PlatformTestUtil.waitWithEventsDispatching("Completion should have started reading the schema", () -> {
            var schema = SqlSchemaService.getInstance().schema(h2);
            return schema != null && schema.isComplete(null);
        }, 30);
    }

    public void testTheTypedHandlerOpensThePopupAfterADotAndAfterClauseWords() {
        loadSchema();
        var handler = new LatticeSqlTypedHandler();
        myFixture.configureByText("query.sql", "select * from books b where b.<caret>");
        assertEquals(TypedHandlerDelegate.Result.STOP, handler.checkAutoPopup('.', getProject(), myFixture.getEditor(), myFixture.getFile()));
        myFixture.configureByText("query.sql", "select * from <caret>");
        assertEquals(TypedHandlerDelegate.Result.STOP, handler.checkAutoPopup(' ', getProject(), myFixture.getEditor(), myFixture.getFile()));
        myFixture.configureByText("query.sql", "select * from books <caret>");
        assertEquals("A space after a table does not open a popup of keywords", TypedHandlerDelegate.Result.CONTINUE,
                handler.checkAutoPopup(' ', getProject(), myFixture.getEditor(), myFixture.getFile()));
        assertNull(LookupManager.getActiveLookup(myFixture.getEditor()));
    }

    public void testTheKeywordCaseSettingOverridesTheTypedCase() {
        loadSchema();
        var settings = SqlEditorSettings.getInstance();
        settings.keywordCase(SqlEditorSettings.KeywordCase.UPPER);
        assertTrue(lookups("select * from books <caret>").containsAll(List.of("WHERE", "ORDER BY")));
        settings.keywordCase(SqlEditorSettings.KeywordCase.LOWER);
        assertTrue(lookups("SELECT * FROM books <caret>").containsAll(List.of("where", "order by")));
        settings.keywordCase(SqlEditorSettings.KeywordCase.AS_TYPED);
        assertTrue(lookups("SELECT * FROM books <caret>").contains("WHERE"));
    }
}
