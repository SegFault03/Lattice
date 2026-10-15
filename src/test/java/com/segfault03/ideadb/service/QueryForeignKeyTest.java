package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class QueryForeignKeyTest {
    @Test void compositeForeignKeysAndOverlappingPrimaryKeysSurviveAliasesAndMetadataEnrichment() throws Exception {
        java.util.Properties credentials = new java.util.Properties();
        credentials.setProperty("user", "SA");
        credentials.setProperty("password", "");
        try (Connection connection = new org.hsqldb.jdbc.JDBCDriver().connect(
                "jdbc:hsqldb:mem:query_foreign_keys;shutdown=true", credentials)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE PARENT_RECORD (TENANT INT, ID INT, PRIMARY KEY(TENANT, ID))");
                statement.execute("CREATE TABLE CHILD_RECORD (TENANT INT, PARENT_ID INT, DETAIL INT, "
                        + "PRIMARY KEY(TENANT, DETAIL), FOREIGN KEY(TENANT, PARENT_ID) REFERENCES PARENT_RECORD(TENANT, ID))");
                statement.execute("INSERT INTO PARENT_RECORD VALUES(1, 7)");
                statement.execute("INSERT INTO CHILD_RECORD VALUES(1, 7, 3)");
            }
            var result = DataService.getInstance().executeQuery(connection, "PUBLIC",
                    "SELECT C.TENANT AS LABEL, C.PARENT_ID AS LABEL, C.DETAIL AS LABEL, P.ID AS LABEL, "
                            + "C.PARENT_ID + 1 AS CALCULATED FROM CHILD_RECORD C "
                            + "JOIN PARENT_RECORD P ON C.TENANT = P.TENANT AND C.PARENT_ID = P.ID");
            assertFalse(result.hasError(), result.getError());
            assertEquals(Set.of(0, 2, 3), result.getPrimaryKeyColumns());
            assertEquals(Set.of(0, 1), result.getForeignKeyColumns());
            var copied = result.withInsertPolicy(Set.of("ID"), "");
            assertEquals(result.getPrimaryKeyColumns(), copied.getPrimaryKeyColumns());
            assertEquals(result.getForeignKeyColumns(), copied.getForeignKeyColumns());

            var columns = MetadataService.getInstance().getColumns(connection,
                    new ConnectionConfig(DatabaseType.HSQLDB, "Foreign-key test"), "PUBLIC", "CHILD_RECORD");
            assertEquals(Set.of("TENANT", "PARENT_ID"), columns.stream().filter(ColumnMetadata::isForeignKey)
                    .map(ColumnMetadata::getName).collect(java.util.stream.Collectors.toSet()));
            assertTrue(columns.stream().filter(c -> c.getName().equals("TENANT")).findFirst().orElseThrow().isPrimaryKey());

            ReadOnlyResultModel model = new ReadOnlyResultModel();
            model.setResult(result);
            assertTrue(model.isForeignKeyColumn(0));
            assertTrue(model.isPrimaryKeyColumn(0));
            assertTrue(model.isForeignKeyColumn(1));
            assertFalse(model.isForeignKeyColumn(3), "The referenced parent's key is not an imported foreign key");
            model.setResult(DataService.getInstance().executeQuery(connection, "PUBLIC", "VALUES(1)"));
            assertFalse(model.isForeignKeyColumn(0), "A later unkeyed result clears relationship styling");
            assertFalse(model.isPrimaryKeyColumn(0));
        }
    }

    @Test void missingForeignKeyMetadataDoesNotPreventDisplayingResults() {
        Connection unsupported = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> { throw new SQLFeatureNotSupportedException("No metadata"); });
        assertEquals(Set.of(), DataService.foreignKeyColumns(unsupported,
                List.of(new DataService.ResultColumn(null, "PUBLIC", "CHILD_RECORD", "PARENT_ID"))));
    }
}
