package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static com.segfault03.ideadb.sql.run.SqlStatementKind.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlStatementKindTest {
    private static final SqlDialectProfile PG = SqlDialects.POSTGRESQL;

    private static SqlStatementKind kind(String sql) { return SqlStatementKind.of(sql, PG); }

    @Test void selectValuesTableAreQuery() {
        assertEquals(QUERY, kind("select 1"));
        assertEquals(QUERY, kind("  SELECT * from t"));
        assertEquals(QUERY, kind("values (1), (2)"));
        assertEquals(QUERY, kind("table t"));
    }

    @Test void dmlVerbs() {
        assertEquals(DML, kind("insert into t values (1)"));
        assertEquals(DML, kind("UPDATE t set a = 1"));
        assertEquals(DML, kind("delete from t"));
        assertEquals(DML, kind("merge into t using s on t.id = s.id when matched then delete"));
        assertEquals(DML, SqlStatementKind.of("insert into t select * from s on duplicate key update a = 1", SqlDialects.MYSQL));
    }

    @Test void replaceIsDmlOnlyForMysql() {
        assertEquals(DML, SqlStatementKind.of("replace into t values (1)", SqlDialects.MYSQL));
        assertEquals(OTHER, SqlStatementKind.of("replace into t values (1)", PG));
        assertEquals(OTHER, SqlStatementKind.of("replace into t values (1)", SqlDialects.H2));
    }

    @Test void withMainVerbDecides() {
        assertEquals(QUERY, kind("with a as (select 1) select * from a"));
        assertEquals(QUERY, kind("with recursive a(n) as (select 1 union all select n + 1 from a), b as not materialized (select 2) select * from a, b"));
        assertEquals(DML, kind("with a as (select 1) insert into t select * from a"));
        assertEquals(DML, kind("with a as (select 1) delete from t where id in (select * from a)"));
    }

    @Test void dataModifyingCteIsDml() {
        assertEquals(DML, kind("with d as (delete from t returning *) select * from d"));
        assertEquals(DML, kind("with u as (update t set a = 1 returning *), s as (select 1) select * from u"));
    }

    @Test void malformedWithIsOther() {
        assertEquals(OTHER, kind("with a as (select 1"));
        assertEquals(OTHER, kind("with a as (select 1)"));
        assertEquals(OTHER, kind("with a as (select 1))"));
        assertEquals(OTHER, kind("with"));
        assertEquals(OTHER, kind("with a select 1"));
    }

    @Test void leadingCommentsSkipped() {
        assertEquals(DML, kind("-- note\n/* more */ update t set a = 1"));
        assertEquals(QUERY, kind("/* x */ -- y\nselect 1"));
    }

    @Test void emptyAndCommentOnlyAreOther() {
        assertEquals(OTHER, kind(""));
        assertEquals(OTHER, kind("   \n"));
        assertEquals(OTHER, kind("-- nothing"));
        assertEquals(OTHER, kind("/* nothing */"));
    }

    @Test void keywordsInsideStringsAndCommentsIgnored() {
        assertEquals(QUERY, kind("select 'delete from t'"));
        assertEquals(DML, kind("/* drop */ update t set a=1"));
        assertEquals(QUERY, kind("select 1 -- into x"));
        assertEquals(QUERY, kind("select 'into' as a"));
        assertEquals(QUERY, kind("select \"into\" from t"));
    }

    @Test void selectIntoIsOther() {
        assertEquals(OTHER, kind("select * into backup from t"));
        assertEquals(OTHER, SqlStatementKind.of("select 1 into @v", SqlDialects.MYSQL));
        assertEquals(OTHER, SqlStatementKind.of("select a from t into outfile '/tmp/x'", SqlDialects.MYSQL));
        assertEquals(OTHER, SqlStatementKind.of("select * into new_t from t", SqlDialects.H2));
        assertEquals(OTHER, kind("with a as (select 1) select * into t2 from a"));
        assertEquals(QUERY, kind("select (select 1 from t) from u"));
    }

    @Test void selectForUpdateIsQuery() {
        assertEquals(QUERY, kind("select * from t for update"));
        assertEquals(QUERY, kind("select * from t for share"));
    }

    private static final List<String> REFUSED = List.of("create table t (a int)", "alter table t add b int", "drop table t", "truncate t", "commit", "rollback",
            "savepoint s", "set x = 1", "call p()", "lock tables t write", "load data infile 'f' into table t", "copy t from stdin",
            "grant all on t to u", "explain select 1", "show tables", "use db", "do 1", "begin", "start transaction",
            "prepare s from 'select 1'", "execute s");

    @TestFactory Stream<DynamicTest> refusedVerbsAreOther() {
        return REFUSED.stream().map(sql -> DynamicTest.dynamicTest(sql, () -> {
            assertEquals(OTHER, kind(sql));
            assertEquals(OTHER, SqlStatementKind.of(sql, SqlDialects.MYSQL));
        }));
    }

    @Test void refusalMessages() {
        assertNull(explainRefusal(QUERY));
        assertNull(explainRefusal(DML));
        assertEquals("Only queries and DML can be explained.", explainRefusal(OTHER));
        assertNull(rollbackRefusal(DML));
        assertEquals("Run and Roll Back is for INSERT, UPDATE, DELETE and MERGE. Use Explain for queries.", rollbackRefusal(QUERY));
        assertEquals("Run and Roll Back runs only INSERT, UPDATE, DELETE and MERGE; this statement could commit or change the schema.",
                rollbackRefusal(OTHER));
    }

    @Test void targetTablesOfUpdateJoinAndInsertSelect() {
        assertEquals(List.of("orders", "customers"),
                SqlStatementKind.targetTables("update orders o join customers c on c.id = o.cid set o.a = 1", SqlDialects.MYSQL));
        assertEquals(List.of("audit.log", "src"),
                SqlStatementKind.targetTables("insert into audit.log select * from src", PG));
        assertEquals(List.of("Mixed Case", "s.t"),
                SqlStatementKind.targetTables("select * from \"Mixed Case\" join s.t on 1=1 join \"Mixed Case\" x on 1=1", PG));
    }

    @Test void targetTablesExcludeCteNames() {
        assertEquals(List.of("t"), SqlStatementKind.targetTables("with a as (select * from t) select * from a", PG));
        assertEquals(List.of("t"), SqlStatementKind.targetTables("select * from (select * from t) d", PG));
        assertEquals(List.of("s", "t"),
                SqlStatementKind.targetTables("with a as (select * from s), b as (select * from a) insert into t select * from b", PG));
    }
}
