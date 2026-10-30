package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSuppressionsTest {
    private static SqlSuppressions of(String text) { return SqlSuppressions.of(text, SqlDialects.MYSQL); }

    private static String apply(String text, SqlSuppressions.Edit edit) {
        return text.substring(0, edit.range().getStartOffset()) + edit.replacement() + text.substring(edit.range().getEndOffset());
    }

    @Test void aCommentDirectlyAboveCoversItsStatementOnly() {
        String text = "select 1;\n-- noinspection A, B\nselect 2;\nselect 3";
        var s = of(text);
        assertFalse(s.isSuppressed(text.indexOf("select 1"), "A"));
        assertTrue(s.isSuppressed(text.indexOf("select 2"), "A"));
        assertTrue(s.isSuppressed(text.indexOf("select 2"), "B"));
        assertFalse(s.isSuppressed(text.indexOf("select 2"), "C"));
        assertFalse(s.isSuppressed(text.indexOf("select 3"), "A"));
    }

    @Test void aTopCommentSeparatedByABlankLineCoversTheFileAndAllCoversEveryName() {
        String text = "# noinspection ALL\n\nselect 1;\nselect 2";
        var s = of(text);
        assertTrue(s.suppressedInFile("Anything"));
        assertTrue(s.isSuppressed(text.indexOf("select 2"), "Anything"));
        assertFalse(of("-- noinspection A\nselect 1").suppressedInFile("A"), "attached to the first statement it is that statement's");
    }

    @Test void ordinaryCommentsAndTrailingCommentsDoNotSuppress() {
        assertFalse(of("-- noinspection\nselect 1").isSuppressed(12, "A"));
        assertFalse(of("select 1; -- noinspection A\nselect 2").isSuppressed(30, "A"));
        assertFalse(of("-- see noinspection A\nselect 1").isSuppressed(25, "A"));
    }

    @Test void theEditsAddACommentOrExtendTheOneThatIsThere() {
        String text = "select 1;\n  select 2";
        assertEquals("select 1;\n  -- noinspection A\n  select 2", apply(text, of(text).forStatement(text.length() - 1, "A")));
        assertEquals("-- noinspection A\n\nselect 1;\n  select 2", apply(text, of(text).forFile("A")));
        String existing = "-- noinspection A\nselect 1";
        assertEquals("-- noinspection A,B\nselect 1", apply(existing, of(existing).forStatement(20, "B")));
        assertEquals(existing, apply(existing, of(existing).forStatement(20, "A")), "already listed: nothing to add");
        String shared = "select 1; select 2";
        assertEquals("select 1; \n-- noinspection A\nselect 2", apply(shared, of(shared).forStatement(shared.length() - 1, "A")));
    }
}
