package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.application.ReadAction;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

import java.util.List;

/** Cards for SQL injected into Java strings: one per injection, hugging the literal. */
public class SqlCardLocatorJavaFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private String source;

    private List<SqlCard> cards(String members) {
        source = "import java.sql.*;\nclass A {\n" + members + "\n}";
        myFixture.configureByText("A.java", source);
        return ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile()));
    }

    private List<String> segmentTexts(SqlCard card) { return card.segments().stream().map(segment -> segment.substring(source)).toList(); }

    public void testSingleLineLiteralIncludesQuotes() {
        List<SqlCard> cards = cards("String q = \"select * from t where id = ?\";");
        assertEquals(1, cards.size());
        assertEquals(List.of("\"select * from t where id = ?\""), segmentTexts(cards.get(0)));
        assertEquals("select * from t where id = ?", cards.get(0).sql());
        assertTrue(cards.get(0).injected());
    }

    public void testTextBlockSegmentsPerLine() {
        List<SqlCard> cards = cards("String q = \"\"\"\n        update orders\n           set status = 'x'\n         where id = ?\n        \"\"\";");
        assertEquals(1, cards.size());
        assertEquals(List.of("update orders", "set status = 'x'", "where id = ?"), segmentTexts(cards.get(0)));
    }

    public void testConcatenationIsOneCard() {
        List<SqlCard> cards = cards("String f(String t, int id) { return \"select * from \" + t + \" where id = \" + id; }");
        assertEquals(1, cards.size());
        assertEquals("select * from ? where id = ?", cards.get(0).sql());
    }

    public void testTwoLiteralsOnOneLineAreTwoCards() {
        List<SqlCard> cards = cards("void f(Connection c) throws Exception { c.prepareStatement(\"select 1 from dual\"); c.prepareStatement(\"select 2 from dual\"); }");
        assertEquals(2, cards.size());
        assertEquals(List.of("select 1 from dual", "select 2 from dual"), cards.stream().map(SqlCard::sql).toList());
        assertTrue(cards.get(0).range().getEndOffset() < cards.get(1).range().getStartOffset());
    }

    public void testMultiStatementStringHasNullKind() {
        SqlCard card = cards("String q = \"delete from a where id = 1; insert into a values (1)\";").get(0);
        assertEquals(2, card.statementCount());
        assertNull(card.kind());
    }

    public void testNotSqlCommentHasNoCard() {
        assertEquals(List.of(), cards("// lattice:not-sql\nString q = \"select * from orders where id = ?\";"));
    }
}
