package com.segfault03.ideadb.sql.validate;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.PlatformTestUtil;
import com.segfault03.ideadb.service.DatabaseChange;
import com.segfault03.ideadb.service.DatabaseChangeListener;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.schema.SqlSchemaTestHooks;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The inspections over a real H2 schema: what they report, how it can be fixed, and when they stay quiet. */
public class SqlValidationFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))"};
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(LatticeSqlSyntaxInspection.class, LatticeSqlUnknownTableInspection.class, LatticeSqlUnknownColumnInspection.class,
                LatticeSqlAmbiguousColumnInspection.class, LatticeSqlAliasInspection.class);
    }

    private List<HighlightInfo> problems(String sql) {
        myFixture.configureByText("q.sql", sql);
        return myFixture.doHighlighting().stream().filter(info -> info.getSeverity().compareTo(HighlightSeverity.WEAK_WARNING) >= 0).toList();
    }

    private static List<String> messages(List<HighlightInfo> infos) { return infos.stream().map(HighlightInfo::getDescription).toList(); }

    public void testValidSqlIsQuietAndMistakesAreFoundWithTheirSeverities() {
        loadSchema();
        assertEquals(List.of(), messages(problems("select a.name, b.title from authors a join books b on b.author_id = a.id where b.title like 'x%'")));

        var infos = problems("select * from authorz");
        assertEquals(1, infos.size());
        assertEquals(HighlightSeverity.WARNING, infos.get(0).getSeverity());
        assertTrue(infos.get(0).getDescription(), infos.get(0).getDescription().contains("Unknown table 'authorz'"));
        assertEquals("authorz", myFixture.getEditor().getDocument().getText(new com.intellij.openapi.util.TextRange(infos.get(0).getStartOffset(), infos.get(0).getEndOffset())));

        var syntax = problems("selct * from books");
        assertEquals(1, syntax.size());
        assertEquals(HighlightSeverity.ERROR, syntax.get(0).getSeverity());

        assertEquals(1, problems("select titel from books").size());
        assertEquals(1, problems("select id from authors a join books b on b.author_id = a.id").size());
        assertEquals(1, problems("select authors.id from authors a").size());
    }

    public void testTheQuickFixPutsTheSuggestedNameInPlace() {
        loadSchema();
        myFixture.configureByText("q.sql", "select titel<caret> from books");
        myFixture.doHighlighting();
        var fix = myFixture.findSingleIntention("Replace with 'TITLE'");
        myFixture.launchAction(fix);
        myFixture.checkResult("select TITLE from books");

        myFixture.configureByText("q2.sql", "selct<caret> * from books");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Replace with 'select'"));
        myFixture.checkResult("select * from books");
    }

    public void testWithoutACachedSchemaOnlyTheShapeOfStatementsIsChecked() {
        // nothing has been read yet, and reading is not started by opening a file
        assertEquals(List.of(), messages(problems("select * from authorz")));
        assertEquals(1, problems("select * from books where;").size());
    }

    public void testAChangeToTheConnectionSuspendsSchemaChecksUntilTheSchemaIsReadAgain() {
        loadSchema();
        assertEquals(1, problems("select * from authorz").size());
        var file = myFixture.getFile();
        var service = SqlSchemaService.getInstance();

        // The refresh that validation asks for is held back, so the stale state can be observed before it ends.
        CountDownLatch release = new CountDownLatch(1);
        SqlSchemaTestHooks.onColumnRead(table -> {
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            DatabaseChangeListener.publish(DatabaseChange.connection(h2.getId()));
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue("a console statement may have changed the schema", service.isStale(h2.getId()));
            assertEquals("nothing is claimed from a cache that may be out of date", List.of(),
                    SqlValidation.diagnostics(file).stream().filter(d -> d.kind() == SqlDiagnostic.Kind.UNKNOWN_TABLE).toList());
            assertTrue("validating a stale schema asks for it to be read again", service.isLoading(h2.getId()));
        } finally {
            release.countDown();
            SqlSchemaTestHooks.reset();
        }

        PlatformTestUtil.waitWithEventsDispatching("The stale schema was never read again", () -> !service.isStale(h2.getId()) && !service.isLoading(h2.getId()), 30);
        assertEquals("the checks come back with the fresh schema", 1, SqlValidation.diagnostics(file).stream().filter(d -> d.kind() == SqlDiagnostic.Kind.UNKNOWN_TABLE).count());
    }

    public void testThePreviewOfTheQuickFixShowsTheReplacement() {
        loadSchema();
        myFixture.configureByText("q.sql", "select titel<caret> from books");
        myFixture.doHighlighting();
        var fix = myFixture.findSingleIntention("Replace with 'TITLE'");
        assertEquals("select TITLE from books", myFixture.getIntentionPreviewText(fix));
        myFixture.checkResult("select titel from books");
        myFixture.checkPreviewAndLaunchAction(fix);
        myFixture.checkResult("select TITLE from books");
    }

    public void testValidationCanBeTurnedOff() {
        loadSchema();
        SqlEditorSettings.getInstance().setValidation(false);
        assertEquals(List.of(), messages(problems("select * from authorz where (")));
    }

    public void testEachKindIsItsOwnInspection() {
        loadSchema();
        myFixture.disableInspections(new LatticeSqlUnknownTableInspection());
        assertEquals(List.of(), messages(problems("select * from authorz")));
        assertEquals(1, problems("select * from authors where;").size());
    }
}
