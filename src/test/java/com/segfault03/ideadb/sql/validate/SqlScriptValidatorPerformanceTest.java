package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation must stay roughly linear in the statement's size, also against a large schema: a script near
 * {@link SqlScriptValidator#MAX_LENGTH} is checked on every edit. The limit is generous for slow CI machines; each case
 * takes a small fraction of it on a developer machine.
 */
class SqlScriptValidatorPerformanceTest {
    private static final int TABLES = 5_000;
    private static final int COLUMNS = 40;
    private static final long LIMIT_MILLIS = 1_500;
    private static final int TARGET_LENGTH = 190_000;

    private static SqlSchema schema;

    @BeforeAll static void buildSchema() {
        List<ExplorerMetadataSnapshot.Table> tables = new ArrayList<>();
        for (int t = 1; t <= TABLES; t++) {
            List<ColumnMetadata> columns = new ArrayList<>();
            for (int c = 0; c < COLUMNS; c++) columns.add(new ColumnMetadata("col_" + c, "INTEGER", Types.INTEGER, 10, 0, true, false, false, null));
            var metadata = new TableMetadata(null, "public", "table_" + t, "TABLE");
            metadata.setColumns(columns);
            tables.add(new ExplorerMetadataSnapshot.Table(metadata, columns, true));
        }
        var snapshot = new ExplorerMetadataSnapshot(List.of(new ExplorerMetadataSnapshot.Namespace("public", tables, true)), List.of(), 3);
        schema = SqlSchema.of(new ConnectionConfig(DatabaseType.POSTGRESQL, "big"), snapshot, "public", 1);
    }

    /** One warm-up run, then the timed run; the findings of the timed run are returned. */
    private static List<SqlDiagnostic> timed(String name, String sql) {
        assertTrue(sql.length() < SqlScriptValidator.MAX_LENGTH, "the input must be small enough to be validated");
        SqlScriptValidator.validate(sql, SqlDialects.POSTGRESQL, schema);
        long start = System.nanoTime();
        var found = SqlScriptValidator.validate(sql, SqlDialects.POSTGRESQL, schema);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("PERF " + name + ": " + millis + " ms, " + sql.length() + " chars, " + found.size() + " findings");
        assertTrue(millis < LIMIT_MILLIS, name + " took " + millis + " ms");
        return found;
    }

    private static String repeated(IntFunction<String> piece, String separator, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; text.length() < length; i++) {
            if (i > 0) text.append(separator);
            text.append(piece.apply(i));
        }
        return text.toString();
    }

    @Test void wideQualifiedSelectList() {
        String sql = "select " + repeated(i -> "t.col_" + i % COLUMNS, ", ", 150_000) + " from table_1 t";
        assertEquals(List.of(), timed("wide qualified select", sql));
    }

    @Test void wideAliasedSelectList() {
        String sql = "select " + repeated(i -> "col_" + i % COLUMNS + " a" + i, " , ", 150_000) + " from table_1";
        assertEquals(List.of(), timed("wide aliased select", sql));
    }

    @Test void longInList() {
        String sql = "select * from table_1 where col_1 in (" + repeated(i -> "col_2", ", ", TARGET_LENGTH) + ")";
        assertEquals(List.of(), timed("long in list", sql));
    }

    @Test void deeplyNestedSubqueries() {
        int depth = 2_000;
        String sql = "select col_1 from table_1 where col_1 in " + "(select col_1 from table_1 where col_1 in ".repeat(depth - 1)
                + "(select col_1 from table_1)" + ")".repeat(depth - 1);
        assertEquals(List.of(), timed("nested subqueries", sql));
    }

    @Test void manyUnknownTables() {
        String sql = repeated(i -> "select * from tabel_" + (i % TABLES + 1) + ";", "\n", TARGET_LENGTH);
        var found = timed("unknown tables", sql);
        assertFalse(found.isEmpty());
        assertTrue(found.stream().allMatch(d -> d.kind() == SqlDiagnostic.Kind.UNKNOWN_TABLE));
    }

    @Test void manyJoinStatements() {
        String sql = repeated(i -> "select a.col_1, b.col_2 from table_" + (i % TABLES + 1) + " a join table_" + (i % 97 + 1)
                + " b on a.col_3 = b.col_3 where a.col_4 > 1 and b.col_5 is not null order by a.col_1;", "\n", TARGET_LENGTH);
        assertEquals(List.of(), timed("join statements", sql));
    }
}
