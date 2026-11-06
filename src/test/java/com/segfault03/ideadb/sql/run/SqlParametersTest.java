package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.run.SqlParameters.Result;
import com.segfault03.ideadb.sql.run.SqlParameters.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlParametersTest {
    private static final String MIXED = "Use either positional (?, $1) or named (:name) parameters, not both";

    @Test void questionMarksArePositionalInOrder() {
        String sql = "select * from t where a = ? and b = ?";
        Result result = SqlParameters.of(sql, SqlDialects.MYSQL, true);
        assertEquals(Style.POSITIONAL, result.style());
        assertEquals(List.of("1", "2"), result.keys());
        assertEquals(2, result.occurrences().size());
        assertEquals("1", result.occurrences().get(0).key());
        assertEquals(sql.indexOf('?'), result.occurrences().get(0).start());
        assertEquals(sql.indexOf('?') + 1, result.occurrences().get(0).end());
        assertEquals("2", result.occurrences().get(1).key());
        assertNull(result.error());
    }

    @Test void dollarNumbersKeyByNumber() {
        Result result = SqlParameters.of("select $2, $1, $2", SqlDialects.POSTGRESQL, false);
        assertEquals(Style.POSITIONAL, result.style());
        assertEquals(List.of("2", "1"), result.keys());
        assertEquals(3, result.occurrences().size());
        assertNull(result.error());
    }

    @Test void repeatedNamedParameterSharesOneKey() {
        String sql = "select * from t where a = :id or b = :Id or c = :id";
        Result result = SqlParameters.of(sql, SqlDialects.ANSI, true);
        assertEquals(Style.NAMED, result.style());
        assertEquals(List.of(":id", ":Id"), result.keys());
        assertEquals(3, result.occurrences().size());
        assertEquals(sql.indexOf(":id"), result.occurrences().get(0).start());
        assertEquals(sql.indexOf(":id") + 3, result.occurrences().get(0).end());
    }

    @Test void mysqlAtNameIsNotAParameter() {
        Result result = SqlParameters.of("select @x, @@version", SqlDialects.MYSQL, true);
        assertEquals(Style.NONE, result.style());
        assertTrue(result.occurrences().isEmpty());
        assertTrue(result.keys().isEmpty());
    }

    @Test void castsAssignmentsAndSlicesAreNotParameters() {
        assertEquals(Style.NONE, SqlParameters.of("select a::text, b[1:2] from t", SqlDialects.POSTGRESQL, true).style());
        assertEquals(Style.NONE, SqlParameters.of("select 1 from t where x[1:2] = 1", SqlDialects.POSTGRESQL, true).style());
        assertEquals(Style.NONE, SqlParameters.of("set @x := 1", SqlDialects.MYSQL, true).style());
        assertEquals(Style.NONE, SqlParameters.of("select a::text", SqlDialects.MYSQL, true).style());
    }

    @Test void parametersInsideStringsAndCommentsIgnored() {
        String sql = "select '?', \":a\", $$ :b $1 $$ -- ? :c\n /* :d ? */ from t where x = :real";
        Result result = SqlParameters.of(sql, SqlDialects.POSTGRESQL, true);
        assertEquals(List.of(":real"), result.keys());
    }

    @Test void postgresQuestionOperatorIsNotAParameterInSqlFiles() {
        assertEquals(Style.NONE, SqlParameters.of("select * from t where data ? 'k'", SqlDialects.POSTGRESQL, false).style());
        assertEquals(Style.NONE, SqlParameters.of("select * from t where data ?| array['a']", SqlDialects.POSTGRESQL, false).style());
        assertEquals(Style.NONE, SqlParameters.of("select * from t where data ?& array['a']", SqlDialects.POSTGRESQL, false).style());
    }

    @Test void postgresQuestionIsParameterInJdbcHost() {
        Result result = SqlParameters.of("select * from t where a = ?", SqlDialects.POSTGRESQL, true);
        assertEquals(Style.POSITIONAL, result.style());
        assertEquals(List.of("1"), result.keys());
    }

    @Test void nonPostgresQuestionIsPositionalEvenOutsideJdbcHost() {
        assertEquals(Style.POSITIONAL, SqlParameters.of("select ?", SqlDialects.H2, false).style());
    }

    @Test void mixedStylesReportError() {
        Result named = SqlParameters.of("select ?, :name", SqlDialects.ANSI, true);
        assertEquals(MIXED, named.error());
        Result dollar = SqlParameters.of("select $1, :name", SqlDialects.POSTGRESQL, true);
        assertEquals(MIXED, dollar.error());
    }

    @Test void questionAndDollarMixTreatedAsPositional() {
        Result result = SqlParameters.of("select ?, $1", SqlDialects.POSTGRESQL, true);
        assertEquals(Style.POSITIONAL, result.style());
        assertNull(result.error());
    }
}
