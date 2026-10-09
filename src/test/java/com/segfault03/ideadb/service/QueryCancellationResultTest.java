package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.QueryResult;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryCancellationResultTest {
    @Test void cancellationDoesNotBecomeASuccessfulUpdateOrQueryError() throws Exception {
        java.util.Properties credentials = new java.util.Properties();
        credentials.setProperty("user", "SA");
        credentials.setProperty("password", "");
        try (Connection connection = new org.hsqldb.jdbc.JDBCDriver().connect("jdbc:hsqldb:mem:p3_cancel", credentials)) {
            QueryExecution execution = new QueryExecution();
            execution.requestCancellation();
            QueryResult cancelled = DataService.getInstance().executeQuery(connection, "PUBLIC", "VALUES(1)",
                    DataService.QueryOptions.defaults(), execution);
            assertTrue(cancelled.isCancelled());
            assertFalse(cancelled.hasError());
            assertFalse(cancelled.isResultSet());
            assertEquals("Query cancelled", cancelled.getMessage());
            assertFalse(execution.isRunning());
            QueryResult later = DataService.getInstance().executeQuery(connection, "PUBLIC", "VALUES(1)");
            assertTrue(later.isResultSet());
            assertFalse(later.isCancelled());
            assertEquals(1, later.getRows().size());
        }
    }

    @Test void cancellationWordsInAnActualFailureDoNotChangeItsOutcome() throws Exception {
        java.util.Properties credentials = new java.util.Properties();
        credentials.setProperty("user", "SA");
        credentials.setProperty("password", "");
        try (Connection connection = new org.hsqldb.jdbc.JDBCDriver().connect("jdbc:hsqldb:mem:p3_failure", credentials)) {
            QueryResult failure = DataService.getInstance().executeQuery(connection, "PUBLIC",
                    "SELECT \"Query cancelled\" FROM (VALUES(1))");
            assertTrue(failure.hasError());
            assertTrue(failure.getError().contains("Query cancelled"), failure.getError());
            assertFalse(failure.isCancelled());
        }
    }
}
