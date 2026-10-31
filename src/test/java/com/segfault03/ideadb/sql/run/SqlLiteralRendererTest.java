package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.run.SqlParameterValue.Type;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlLiteralRendererTest {
    private static SqlDialectProfile profile(DatabaseType type) {
        return switch (type) {
            case MYSQL -> SqlDialects.MYSQL;
            case POSTGRESQL -> SqlDialects.POSTGRESQL;
            case HSQLDB -> SqlDialects.HSQLDB;
            case H2 -> SqlDialects.H2;
        };
    }

    private static String render(String sql, DatabaseType type, Map<String, SqlParameterValue> values) {
        return SqlLiteralRenderer.render(sql, SqlParameters.of(sql, profile(type), false), values, type);
    }

    private static SqlParameterValue v(Type type, String text) { return new SqlParameterValue(type, text); }

    @Test void textQuotesEscapedPerDialect() {
        String text = "O'Brien \\ x";
        for (DatabaseType type : DatabaseType.values()) {
            String expected = DatabaseProviders.get(type).sqlLiteral(text, "VARCHAR");
            assertEquals("select " + expected, render("select :t", type, Map.of(":t", v(Type.TEXT, text))), type.name());
        }
        // PostgreSQL E'' doubles the backslash; H2 and HSQLDB read it literally; MySQL hex-encodes the value.
        assertEquals("select E'O''Brien \\\\ x'", render("select :t", DatabaseType.POSTGRESQL, Map.of(":t", v(Type.TEXT, text))));
        assertEquals("select 'O''Brien \\ x'", render("select :t", DatabaseType.H2, Map.of(":t", v(Type.TEXT, text))));
        assertEquals("select 'O''Brien \\ x'", render("select :t", DatabaseType.HSQLDB, Map.of(":t", v(Type.TEXT, text))));
        String mysql = render("select :t", DatabaseType.MYSQL, Map.of(":t", v(Type.TEXT, text)));
        assertFalse(mysql.contains("'O'"), mysql);
        assertFalse(mysql.contains("\\"), mysql);
        assertTrue(mysql.contains("utf8mb4"), mysql);
    }

    @Test void numberBooleanNullDateTimestamp() {
        for (DatabaseType type : DatabaseType.values()) {
            String n = type.name();
            assertEquals("select 12.50", render("select :v", type, Map.of(":v", v(Type.NUMBER, " 12.50 "))), n);
            assertEquals("select TRUE", render("select :v", type, Map.of(":v", v(Type.BOOLEAN, "True"))), n);
            assertEquals("select FALSE", render("select :v", type, Map.of(":v", v(Type.BOOLEAN, "FALSE"))), n);
            assertEquals("select NULL", render("select :v", type, Map.of(":v", v(Type.NULL, ""))), n);
            assertEquals("select DATE '2024-02-29'", render("select :v", type, Map.of(":v", v(Type.DATE, "2024-02-29"))), n);
            assertEquals("select TIMESTAMP '2024-02-29 10:11:12'",
                    render("select :v", type, Map.of(":v", v(Type.TIMESTAMP, "2024-02-29 10:11:12"))), n);
            assertEquals("select TIMESTAMP '2024-02-29 10:11:12.123456789'",
                    render("select :v", type, Map.of(":v", v(Type.TIMESTAMP, "2024-02-29 10:11:12.123456789"))), n);
        }
    }

    @Test void valueContainingParameterTextIsNotReplacedAgain() {
        String out = render("select :x, :y", DatabaseType.H2,
                Map.of(":x", v(Type.TEXT, "? :x $1"), ":y", v(Type.NUMBER, "2")));
        assertEquals("select '? :x $1', 2", out);
    }

    @Test void repeatedNamedParameterReplacedEverywhere() {
        assertEquals("select 7 + 7 + 8", render("select :a + :a + :b", DatabaseType.H2,
                Map.of(":a", v(Type.NUMBER, "7"), ":b", v(Type.NUMBER, "8"))));
    }

    @Test void positionalFormsRender() {
        String sql = "select ?, ?, $1";
        SqlParameters.Result params = SqlParameters.of(sql, SqlDialects.POSTGRESQL, true);
        assertEquals("select 1, 2, 1", SqlLiteralRenderer.render(sql, params,
                Map.of("1", v(Type.NUMBER, "1"), "2", v(Type.NUMBER, "2")), DatabaseType.POSTGRESQL));
        assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("1")),
                SqlLiteralRenderer.toJdbc(sql, params, Map.of("1", v(Type.NUMBER, "1"), "2", v(Type.NUMBER, "2"))).bindings());
    }

    @Test void toJdbcRewritesEveryFormToQuestionMarksInOrder() {
        String sql = "select :a, $2, :a";
        // mixed styles are an error result, so build the occurrences by hand
        SqlParameters.Result params = new SqlParameters.Result(SqlParameters.Style.NAMED, List.of(
                new SqlParameters.Occurrence(":a", 7, 9),
                new SqlParameters.Occurrence("2", 11, 13),
                new SqlParameters.Occurrence(":a", 15, 17)), List.of(":a", "2"), null);
        SqlLiteralRenderer.JdbcStatement stmt = SqlLiteralRenderer.toJdbc(sql, params,
                Map.of(":a", v(Type.TEXT, "a"), "2", v(Type.NUMBER, "2")));
        assertEquals("select ?, ?, ?", stmt.sql());
        assertEquals(List.of("a", new BigDecimal("2"), "a"), stmt.bindings());
    }

    @Test void toJdbcBindsPerOccurrenceForRepeatedAndCollidingKeys() {
        String sql = "select :a, :b, :a";
        SqlParameters.Result params = SqlParameters.of(sql, SqlDialects.H2, false);
        SqlLiteralRenderer.JdbcStatement stmt = SqlLiteralRenderer.toJdbc(sql, params,
                Map.of(":a", v(Type.TEXT, "x"), ":b", v(Type.NULL, "")));
        assertEquals("select ?, ?, ?", stmt.sql());
        assertEquals(java.util.Arrays.asList("x", null, "x"), stmt.bindings());
    }

    @Test void jdbcValueTypes() {
        assertEquals(java.sql.Date.valueOf("2024-02-29"), v(Type.DATE, "2024-02-29").jdbcValue());
        assertEquals(java.sql.Timestamp.valueOf("2024-02-29 10:11:12.5"), v(Type.TIMESTAMP, "2024-02-29 10:11:12.5").jdbcValue());
        assertEquals(new BigDecimal("1.50"), v(Type.NUMBER, " 1.50 ").jdbcValue());
        assertEquals(Boolean.TRUE, v(Type.BOOLEAN, "TRUE").jdbcValue());
        assertEquals(Boolean.FALSE, v(Type.BOOLEAN, "false").jdbcValue());
        assertNull(v(Type.NULL, "").jdbcValue());
        assertEquals(" a ", v(Type.TEXT, " a ").jdbcValue());
    }

    @Test void invalidValueRejected() {
        assertNotNull(v(Type.NUMBER, "1,5").validationError());
        assertNotNull(v(Type.NUMBER, "").validationError());
        assertNotNull(v(Type.DATE, "2024-13-01").validationError());
        assertNotNull(v(Type.DATE, "2023-02-29").validationError());
        assertNotNull(v(Type.TIMESTAMP, "2024-01-01").validationError());
        assertNotNull(v(Type.TIMESTAMP, "2024-01-01 10:00:00.1234567890").validationError());
        assertNotNull(v(Type.BOOLEAN, "yes").validationError());
        assertNull(v(Type.TEXT, "").validationError());
        assertNull(v(Type.NULL, "").validationError());
        assertNull(v(Type.TIMESTAMP, "2024-01-01 10:00:00.1").validationError());
        assertThrows(IllegalArgumentException.class,
                () -> render("select :v", DatabaseType.H2, Map.of(":v", v(Type.NUMBER, "1,5"))));
        assertThrows(IllegalArgumentException.class,
                () -> render("select :v", DatabaseType.H2, Map.of()));
    }

    @Test void errorResultRejected() {
        String sql = "select ?, :a";
        SqlParameters.Result params = SqlParameters.of(sql, SqlDialects.H2, false);
        assertNotNull(params.error());
        assertThrows(IllegalArgumentException.class, () -> SqlLiteralRenderer.render(sql, params, Map.of(), DatabaseType.H2));
        assertThrows(IllegalArgumentException.class, () -> SqlLiteralRenderer.toJdbc(sql, params, Map.of()));
    }
}
