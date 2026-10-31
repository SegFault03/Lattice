package com.segfault03.ideadb.service;

import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryChangeTrackingTest {
    @Test void anExecutionOnlyReportsPossibleChangesOnceSqlStartedWithoutAQueryOnlyOutcome() {
        var execution = new QueryExecution();
        assertFalse(execution.mayHaveChangedData(), "Nothing ran yet");
        execution.markSqlStarted();
        assertTrue(execution.mayHaveChangedData(), "Started SQL is ambiguous until proven a read");
        execution.markQueryOnly();
        assertFalse(execution.mayHaveChangedData());
    }

    @Test void leadingKeywordsSeparateReadsFromWrites() {
        for (String read : new String[]{"select 1", "  /* c */ SELECT 1", "-- note\nshow tables", "(select 1)", "explain select 1", "WITH x AS (SELECT 1) SELECT * FROM x"})
            assertTrue(DataService.isReadOnlyQuery(read), read);
        for (String write : new String[]{"insert into t values (1)", "CALL proc()", "update t set a = 1", "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "create table t(a int)"})
            assertFalse(DataService.isReadOnlyQuery(write), write);
    }

    private static java.util.Properties credentials() {
        var properties = new java.util.Properties();
        properties.setProperty("user", "SA"); properties.setProperty("password", "");
        return properties;
    }

    @Test void plainSelectsAreQueryOnlyWhileWritesAndScriptsAreNot() throws Exception {
        try (Connection connection = new org.hsqldb.jdbc.JDBCDriver().connect("jdbc:hsqldb:mem:query_change_" + System.nanoTime() + ";shutdown=true", credentials())) {
            var service = DataService.getInstance();
            var options = DataService.QueryOptions.defaults();
            var select = new QueryExecution();
            assertFalse(service.executeQuery(connection, "PUBLIC", "VALUES 1", options, select).hasError());
            assertTrue(select.hasStartedSql());
            assertFalse(select.mayHaveChangedData(), "A plain SELECT must not trigger reloads");

            var ddl = new QueryExecution();
            service.executeQuery(connection, "PUBLIC", "CREATE TABLE t(a INT)", options, ddl);
            assertTrue(ddl.mayHaveChangedData());

            var script = new QueryExecution();
            service.executeQuery(connection, "PUBLIC", "VALUES 1; INSERT INTO t VALUES (1)", options, script);
            assertTrue(script.mayHaveChangedData(), "A script with a trailing write is ambiguous");
        }
    }
}
