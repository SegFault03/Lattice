package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.application.ReadAction;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.run.SqlStatementKind;

import java.util.List;

/** One card per statement of a .sql file. */
public class SqlCardLocatorFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private List<SqlCard> cards(String text) {
        myFixture.configureByText("q.sql", text);
        return ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile()));
    }

    public void testOneCardPerStatement() {
        String text = "select 1;\n-- c\n\nupdate t set a = 1;\nselect 3";
        List<SqlCard> cards = cards(text);
        assertEquals(List.of(SqlStatementKind.QUERY, SqlStatementKind.DML, SqlStatementKind.QUERY), cards.stream().map(SqlCard::kind).toList());
        assertEquals(List.of("select 1", "update t set a = 1", "select 3"), cards.stream().map(SqlCard::sql).toList());
        assertEquals(List.of("select 1", "update t set a = 1", "select 3"), cards.stream().map(card -> card.range().substring(text)).toList());
        assertFalse(cards.get(0).injected());
        assertEquals(1, cards.get(1).statementCount());
    }

    public void testMultiLineStatementHasASegmentPerLine() {
        String text = "select a,\n       b\n  from t;";
        SqlCard card = cards(text).get(0);
        assertEquals(List.of("select a,", "b", "from t"), card.segments().stream().map(segment -> segment.substring(text)).toList());
    }

    public void testCommentOnlyFileHasNoCards() {
        assertEquals(List.of(), cards("-- nothing here\n/* or here */"));
    }
}
