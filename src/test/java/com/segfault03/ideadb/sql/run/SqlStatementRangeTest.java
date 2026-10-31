package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlStatementRangeTest {
    private static String at(String text, SqlStatementRange range) { return text.substring(range.start(), range.end()); }

    @Test void atCaretReturnsEnclosingStatement() {
        String text = "select 1;\n  select  2 ;\nselect 3;";
        SqlStatementRange range = SqlStatementRange.atCaret(text, text.indexOf("select  2") + 4, SqlDialects.ANSI);
        assertNotNull(range);
        assertEquals("select  2", at(text, range));
    }

    @Test void semicolonInsideStringCommentAndDollarBodyDoesNotSplit() {
        String string = "select 'a;b' as x;\nselect 2;";
        assertEquals("select 'a;b' as x", at(string, SqlStatementRange.atCaret(string, 3, SqlDialects.ANSI)));

        String comment = "select 1 /* ; */ + 1; select 2";
        assertEquals("select 1 /* ; */ + 1", at(comment, SqlStatementRange.atCaret(comment, 3, SqlDialects.ANSI)));

        String dollar = "create function f() returns int as $$ begin; return 1; end $$ language plpgsql;\nselect 2;";
        assertEquals(dollar.substring(0, dollar.indexOf(";\nselect 2")),
                at(dollar, SqlStatementRange.atCaret(dollar, dollar.indexOf("return"), SqlDialects.POSTGRESQL)));

        String tagged = "do $body$ begin perform 1; end $body$;\nselect 2;";
        assertEquals("do $body$ begin perform 1; end $body$",
                at(tagged, SqlStatementRange.atCaret(tagged, tagged.indexOf("perform"), SqlDialects.POSTGRESQL)));

        String mysql = "select /*! 1; */ 2; select 3";
        assertEquals("select /*! 1; */ 2", at(mysql, SqlStatementRange.atCaret(mysql, 3, SqlDialects.MYSQL)));

        String h2 = "select 1 // a; b\n + 2; select 3";
        assertEquals("select 1 // a; b\n + 2", at(h2, SqlStatementRange.atCaret(h2, 3, SqlDialects.H2)));
    }

    @Test void caretRightAfterSemicolonPicksPrecedingStatement() {
        String text = "select 1;\nselect 2;";
        assertEquals("select 1", at(text, SqlStatementRange.atCaret(text, 9, SqlDialects.ANSI)));
    }

    @Test void caretOnBlankLineAfterLastStatementPicksIt() {
        String text = "select 1;\nselect 2;\n\n-- done\n";
        assertEquals("select 2", at(text, SqlStatementRange.atCaret(text, text.length() - 1, SqlDialects.ANSI)));
        assertEquals("select 2", at(text, SqlStatementRange.atCaret(text, text.indexOf("\n\n") + 1, SqlDialects.ANSI)));
    }

    @Test void caretInLeadingWhitespaceGivesNull() {
        String text = "\n  \nselect 1;";
        assertNull(SqlStatementRange.atCaret(text, 1, SqlDialects.ANSI));
    }

    @Test void selectionWithTwoStatementsReturnsTwo() {
        String text = "xx select 1; -- c\nselect ';' ; yy";
        List<SqlStatementRange> ranges = SqlStatementRange.inSelection(text, 3, text.length() - 3, SqlDialects.ANSI);
        assertEquals(List.of("select 1", "select ';'"), ranges.stream().map(r -> at(text, r)).toList());
    }

    @Test void commentOnlySelectionReturnsEmpty() {
        String text = "-- nothing; here\n/* or ; here */;";
        assertTrue(SqlStatementRange.inSelection(text, 0, text.length(), SqlDialects.ANSI).isEmpty());
    }

    @Test void fiveMegabyteDumpResolvesUnder200ms() {
        StringBuilder text = new StringBuilder();
        String line = "INSERT INTO orders (id, total) VALUES (%d, 1);\n";
        int i = 0;
        while (text.length() < 5_000_000) text.append(line.formatted(i++));
        int caret = text.indexOf("VALUES (" + (i / 2) + ",") + 3;
        SqlStatementRange.atCaret(text, caret, SqlDialects.POSTGRESQL);
        long begin = System.nanoTime();
        SqlStatementRange range = SqlStatementRange.atCaret(text, caret, SqlDialects.POSTGRESQL);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        assertTrue(elapsedMs < 200, "took " + elapsedMs + " ms");
        assertNotNull(range);
        assertEquals(line.formatted(i / 2).strip().replace(";", ""), text.substring(range.start(), range.end()));
    }
}
