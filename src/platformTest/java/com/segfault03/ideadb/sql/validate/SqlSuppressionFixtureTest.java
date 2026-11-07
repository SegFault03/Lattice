package com.segfault03.ideadb.sql.validate;

import com.intellij.lang.annotation.HighlightSeverity;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

import java.util.List;

/** {@code -- noinspection} comments over a real H2 schema, and the fixes that write them. */
public class SqlSuppressionFixtureTest extends H2SqlFixtureTestCase {
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
        loadSchema();
    }

    private List<String> problems(String sql) {
        myFixture.configureByText("q.sql", sql);
        return myFixture.doHighlighting().stream().filter(info -> info.getSeverity().compareTo(HighlightSeverity.WEAK_WARNING) >= 0)
                .map(info -> info.getDescription()).toList();
    }

    public void testAStatementCommentSilencesOnlyTheNamedInspectionOfThatStatement() {
        assertEquals(2, problems("select * from authorz;\nselect titel from books").size());
        assertEquals("only the statement below the comment", 1, problems("-- noinspection LatticeSqlUnknownTable\nselect * from authorz;\nselect titel from books").size());
        assertEquals("another inspection's finding in the same statement stays", 1,
                problems("-- noinspection LatticeSqlUnknownTable\nselect titel from books").size());
        assertEquals(0, problems("-- noinspection LatticeSqlUnknownColumn\nselect titel from books").size());
        assertEquals("a list names several", 0, problems("-- noinspection LatticeSqlSyntax, LatticeSqlUnknownColumn\nselect titel from books").size());
        assertEquals("a list without the name does nothing", 1, problems("-- noinspection LatticeSqlSyntax, LatticeSqlAlias\nselect titel from books").size());
        assertEquals("the statement after it is not covered", 1,
                problems("select 1;\n-- noinspection LatticeSqlUnknownTable\nselect * from books;\nselect * from authorz").size());
        assertEquals("ALL silences everything for the statement", 0, problems("-- noinspection ALL\nselect titel from books").size());
    }

    public void testTheCommentMustStandDirectlyAboveTheStatementOnItsOwnLine() {
        assertEquals("a blank line between the comment and a later statement ends its reach", 1,
                problems("select 1;\n-- noinspection LatticeSqlUnknownTable\n\nselect * from authorz").size());
        assertEquals("a comment after another statement on its line is not the next statement's", 1,
                problems("select 1; -- noinspection LatticeSqlUnknownTable\nselect * from authorz").size());
        assertEquals("a text comment above it does not matter", 0,
                problems("select 1;\n-- noinspection LatticeSqlUnknownTable\n-- the reason\nselect * from authorz").size());
    }

    public void testACommentAtTheTopOfTheFileSeparatedByABlankLineSilencesTheWholeFile() {
        assertEquals(0, problems("-- noinspection LatticeSqlUnknownTable\n\nselect * from authorz;\nselect * from bookz").size());
        assertEquals("other inspections still report", 1,
                problems("-- noinspection LatticeSqlUnknownTable\n\nselect * from authorz;\nselect titel from books").size());
        assertEquals(0, problems("-- noinspection ALL\n\nselect titel from authorz;\nselect * from bookz").size());
        assertEquals("directly above the first statement it covers that statement only", 1,
                problems("-- noinspection LatticeSqlUnknownTable\nselect * from authorz;\nselect * from bookz").size());
    }

    public void testTheStatementFixInsertsTheCommentAboveTheStatement() {
        myFixture.configureByText("q.sql", "select 1;\nselect * from authorz<caret>;\nselect * from bookz");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for statement"));
        myFixture.checkResult("select 1;\n-- noinspection LatticeSqlUnknownTable\nselect * from authorz;\nselect * from bookz");
        assertEquals(1, myFixture.doHighlighting().stream().filter(info -> info.getSeverity().compareTo(HighlightSeverity.WEAK_WARNING) >= 0).count());
    }

    public void testTheStatementFixKeepsTheIndentationAndExtendsAnExistingComment() {
        myFixture.configureByText("q.sql", "select 1;\n  select titel<caret> from books");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for statement"));
        myFixture.checkResult("select 1;\n  -- noinspection LatticeSqlUnknownColumn\n  select titel from books");

        myFixture.configureByText("q2.sql", "-- noinspection LatticeSqlUnknownTable\nselect titel<caret> from books");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for statement"));
        myFixture.checkResult("-- noinspection LatticeSqlUnknownTable,LatticeSqlUnknownColumn\nselect titel from books");
        assertEquals(List.of(), problems(myFixture.getFile().getText()));
    }

    public void testTheFileFixInsertsTheCommentAtTheTopWithABlankLine() {
        myFixture.configureByText("q.sql", "select * from authorz<caret>;\nselect * from bookz");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for file"));
        myFixture.checkResult("-- noinspection LatticeSqlUnknownTable\n\nselect * from authorz;\nselect * from bookz");
        assertEquals(List.of(), problems(myFixture.getFile().getText()));
    }

    public void testASyntaxErrorCanBeSilencedToo() {
        assertEquals(1, problems("selct * from books").size());
        assertEquals(0, problems("-- noinspection LatticeSqlSyntax\nselct * from books").size());
        myFixture.configureByText("q.sql", "selct<caret> * from books");
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for statement"));
        myFixture.checkResult("-- noinspection LatticeSqlSyntax\nselct * from books");
    }
}
