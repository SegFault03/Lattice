package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.completion.SqlContext.Position;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.doc.SqlReferenceResolver;
import com.segfault03.ideadb.sql.doc.SqlTarget;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Completion and hover over a database dump and over a schema with thousands of tables must stay quick and correct. */
class SqlLargeInputTest {
    /** A dump-like text: many one-line INSERTs and a few multi-line statements. */
    private static String dump(int statements) {
        StringBuilder text = new StringBuilder("-- dump\nCREATE TABLE orders (id int, total int);\n");
        for (int i = 0; i < statements; i++) {
            text.append("INSERT INTO orders (id, total) VALUES (").append(i).append(", ").append(i * 3).append(");\n");
        }
        return text.toString();
    }

    @Test void aWindowIsTheWholeTextWhenItIsSmallAndTheStatementAroundTheCaretWhenItIsNot() {
        String small = dump(10);
        assertEquals(new SqlTextWindow.Range(0, small.length()), SqlTextWindow.around(small, 20));

        String large = dump(20_000);
        assertTrue(large.length() > 1_000_000);
        int caret = large.indexOf("VALUES (10000,") + 5;
        var window = SqlTextWindow.around(large, caret);
        assertTrue(window.length() < 200, "one statement: " + window.length());
        String statement = large.substring(window.start(), window.end()).strip();
        assertTrue(statement.startsWith("INSERT INTO orders") && statement.endsWith(";"), statement);
        assertTrue(window.start() <= caret && caret <= window.end());
    }

    @Test void aLargeTextWithoutBoundariesStillGetsABoundedWindow() {
        String text = "select " + "a, ".repeat(200_000) + "b from t";
        var window = SqlTextWindow.around(text, 300_000);
        assertTrue(window.length() <= 2 * SqlTextWindow.REACH + 1, "bounded: " + window.length());
        assertTrue(window.start() <= 300_000 && 300_000 <= window.end());
    }

    @Test void aSemicolonInsideALineIsNotAStatementBoundary() {
        String text = "select ';' ,\n".repeat(20_000) + "select * from orders o where o.\n";
        int caret = text.length() - 1;
        var context = SqlStatementAnalyzer.analyze(text, caret, SqlDialects.MYSQL);
        assertEquals(List.of("o"), context.qualifier());
    }

    @Test void analysisInTheMiddleOfAHugeDumpAgreesWithAnalysisOfTheStatementAlone() {
        String large = dump(30_000);
        String statement = "SELECT o.  FROM orders o WHERE o.id = 1;\n";
        int middle = large.indexOf("VALUES (15000,");
        int end = large.indexOf(";\n", middle) + 2;
        String text = large.substring(0, end) + statement + large.substring(end);
        int caret = end + statement.indexOf("o.") + 2;
        var inDump = SqlStatementAnalyzer.analyze(text, caret, SqlDialects.POSTGRESQL);
        var alone = SqlStatementAnalyzer.analyze(statement, statement.indexOf("o.") + 2, SqlDialects.POSTGRESQL);
        assertEquals(Position.SELECT_LIST, inDump.position());
        assertEquals(alone.position(), inDump.position());
        assertEquals(alone.qualifier(), inDump.qualifier());
        assertEquals(alone.tables(), inDump.tables());
        assertEquals(alone.prefixStart() + end, inDump.prefixStart(), "offsets are in the whole text again");
    }

    @Test void completionContextsOverAHugeFileAreFast() {
        String large = dump(50_000);
        int caret = large.indexOf("VALUES (25000,") + 2;
        assertTimeout(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 200; i++) SqlStatementAnalyzer.analyze(large, caret, SqlDialects.MYSQL);
        });
    }

    @Test void hoverOverAHugeFileIsFastAndStillFindsTheName() {
        var schema = bigSchema(5, 3);
        String large = dump(50_000).replace("INSERT INTO orders (id, total) VALUES (25000, 75000);", "SELECT * FROM table_2 WHERE 1 = 1;");
        int offset = large.indexOf("table_2") + 2;
        assertTimeout(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 100; i++) assertEquals(new SqlTarget.Table("PUBLIC", "table_2"), SqlReferenceResolver.resolve(large, offset, SqlDialects.H2, schema));
        });
    }

    // ---- many tables -----------------------------------------------------------------------------------------------

    private static SqlSchema bigSchema(int tables, int columns) {
        List<ExplorerMetadataSnapshot.Table> list = new ArrayList<>();
        for (int t = 0; t < tables; t++) {
            var metadata = new TableMetadata(null, "PUBLIC", "table_" + t, "TABLE");
            List<ColumnMetadata> cols = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                var column = new ColumnMetadata("col_" + c, "VARCHAR(40)", java.sql.Types.VARCHAR, 40, 0, true, c == 0, false, null);
                if (c == 1 && t > 0) column = column.withReference(new ColumnReference("PUBLIC", "table_" + (t - 1), "col_0"));
                cols.add(column);
            }
            metadata.setColumns(cols);
            list.add(new ExplorerMetadataSnapshot.Table(metadata, cols, true));
        }
        var snapshot = new ExplorerMetadataSnapshot(List.of(new ExplorerMetadataSnapshot.Namespace("PUBLIC", list, true)), List.of(), 3);
        return SqlSchema.of(new ConnectionConfig(DatabaseType.H2, "big"), snapshot, "PUBLIC", 1);
    }

    @Test void suggestionsOverThousandsOfTablesStayQuick() {
        var schema = bigSchema(5_000, 40);
        var profile = SqlDialects.H2;
        assertTimeout(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < 20; i++) {
                var tables = SqlCompletionEngine.suggest(SqlStatementAnalyzer.analyze("select * from ", 14, profile), profile, schema);
                assertEquals(5_000, tables.stream().filter(s -> s.kind() == SqlSuggestion.Kind.TABLE).count());
                var columns = SqlCompletionEngine.suggest(SqlStatementAnalyzer.analyze("select t. from table_77 t", 9, profile), profile, schema);
                assertEquals(40, columns.stream().filter(s -> s.kind() == SqlSuggestion.Kind.COLUMN).count());
            }
        });
    }

    @Test void aJoinAmongThousandsOfTablesOnlyLooksAtTheTablesInTheStatement() {
        var schema = bigSchema(5_000, 40);
        var profile = SqlDialects.H2;
        String sql = "select * from table_100 a join table_99 b on ";
        var suggestions = SqlCompletionEngine.suggest(SqlStatementAnalyzer.analyze(sql, sql.length(), profile), profile, schema);
        assertEquals("a.\"col_1\" = b.\"col_0\"", suggestions.get(0).label(), "H2 folds to upper case, so the lower-case names are quoted");
        assertEquals(SqlSuggestion.Kind.JOIN, suggestions.get(0).kind());
    }
}
