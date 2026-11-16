package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlScriptTest {
    private static List<String> sql(SqlScript script) { return script.statements().stream().map(SqlScript.Statement::sql).toList(); }

    @Test void splitsOnTopLevelSemicolonsWithLines() {
        String text = "-- header\nselect 1;\n\ninsert into t values ('a;b'); /* c; */\nupdate t\n  set a = 2";
        var script = SqlScript.parse(text, SqlDialects.H2);
        assertEquals(List.of("select 1", "insert into t values ('a;b')", "update t\n  set a = 2"), sql(script));
        assertEquals(List.of(2, 4, 5), script.statements().stream().map(SqlScript.Statement::line).toList());
        assertEquals(List.of(1, 2, 3), script.statements().stream().map(SqlScript.Statement::index).toList());
    }

    @Test void commentOnlyScriptsHaveNoStatements() {
        assertTrue(SqlScript.parse("-- nothing\n/* here */\n;;", SqlDialects.MYSQL).statements().isEmpty());
    }

    @Test void mysqlDelimiterLinesChangeTheTerminator() {
        String text = """
                create table t (a int);
                DELIMITER $$
                create procedure p()
                begin
                  insert into t values (1);
                  select '$$ in a string';
                end$$
                DELIMITER ;
                call p();
                """;
        var script = SqlScript.parse(text, SqlDialects.MYSQL);
        assertEquals(3, script.statements().size(), sql(script).toString());
        assertEquals("create table t (a int)", script.statements().get(0).sql());
        assertTrue(script.statements().get(1).sql().startsWith("create procedure p()"));
        assertTrue(script.statements().get(1).sql().endsWith("end"));
        assertEquals(3, script.statements().get(1).line());
        assertEquals("call p()", script.statements().get(2).sql());
        assertEquals(9, script.statements().get(2).line());
    }

    @Test void delimiterIsOnlyAClientCommandOnMySql() {
        var script = SqlScript.parse("DELIMITER $$\nselect 1$$", SqlDialects.POSTGRESQL);
        assertEquals(1, script.statements().size());
    }

    @Test void summaryFlattensAndShortens() {
        var statement = SqlScript.parse("select   a,\n b from some_long_table_name", SqlDialects.H2).statements().get(0);
        assertEquals("select a, b from some_long_table_name", statement.summary(80));
        assertEquals("select a, b…", statement.summary(12));
    }
}
