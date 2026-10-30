package com.segfault03.ideadb.sql.completion;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.sql.FixtureConnections;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.settings.SqlFileTypeClaim;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.MetadataCacheState;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Completion in a real editor over an H2 schema read through the schema service. */
public class SqlCompletionFixtureTest extends BasePlatformTestCase {
    private DatabaseSettingsState connections;
    private ConnectionConfig h2;

    @Override protected void setUp() throws Exception {
        super.setUp();
        connections = FixtureConnections.install(getTestRootDisposable(), true);
        SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
        SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        SqlFileTypeClaim.apply(true);
        h2 = new ConnectionConfig(DatabaseType.H2, "completion H2");
        h2.setDatabaseName("completion_" + UUID.randomUUID());
        connections.addConnection(h2);
        Connection connection = DatabaseConnectionManager.getInstance().getConnection(h2);
        synchronized (connection) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))");
                statement.execute("CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))");
            }
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            DatabaseConnectionManager.getInstance().closeConnection(h2.getId());
            MetadataCacheState.getInstance().remove(h2.getId());
            SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
            SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        } finally {
            super.tearDown();
        }
    }

    /** Completion starts the read itself; tests that need the schema ready ask for it up front. */
    private void loadSchema() {
        SqlSchemaService service = SqlSchemaService.getInstance();
        service.ensureLoaded(h2);
        PlatformTestUtil.waitWithEventsDispatching("The schema never loaded", () -> {
            var schema = service.schema(h2);
            return schema != null && schema.isComplete(null);
        }, 30);
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
}
