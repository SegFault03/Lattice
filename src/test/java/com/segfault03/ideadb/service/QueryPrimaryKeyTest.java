package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ReadOnlyResultModel;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class QueryPrimaryKeyTest {
    @Test void resultKeysFollowSourceColumnsAcrossAliasesJoinsAndCompositeKeys() throws Exception {
        java.util.Properties credentials = new java.util.Properties();
        credentials.setProperty("user", "SA");
        credentials.setProperty("password", "");
        try (Connection connection = new org.hsqldb.jdbc.JDBCDriver().connect("jdbc:hsqldb:mem:query_keys;shutdown=true", credentials)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE KEYED (TENANT INT, ID INT, PRIMARY KEY(TENANT, ID))");
                statement.execute("CREATE TABLE UNKEYED (ID INT)");
                statement.execute("INSERT INTO KEYED VALUES(1, 7)");
                statement.execute("INSERT INTO UNKEYED VALUES(7)");
            }
            var result = DataService.getInstance().executeQuery(connection, "PUBLIC",
                    "SELECT A.TENANT AS DISPLAY, A.ID AS DISPLAY, B.ID AS DISPLAY, A.TENANT + 1 AS CALCULATED "
                            + "FROM KEYED A JOIN UNKEYED B ON A.ID = B.ID");
            assertFalse(result.hasError(), result.getError());
            assertEquals(Set.of(0, 1), result.getPrimaryKeyColumns());
            assertEquals(Set.of(0, 1), DataService.primaryKeyColumns(connection, List.of(
                    new DataService.ResultColumn("", "PUBLIC", "KEYED", "TENANT"),
                    new DataService.ResultColumn("", "PUBLIC", "KEYED", "ID"))),
                    "Unreported catalogs must not exclude otherwise valid key metadata");
            assertEquals(List.of("DISPLAY", "DISPLAY", "DISPLAY", "CALCULATED"), result.getColumnNames());
            assertEquals(Set.of(0, 1), result.withInsertPolicy(Set.of("ID"), "").getPrimaryKeyColumns());
            ReadOnlyResultModel model = new ReadOnlyResultModel();
            model.setResult(result);
            assertTrue(model.isPrimaryKeyColumn(0));
            assertTrue(model.isPrimaryKeyColumn(1));
            assertFalse(model.isPrimaryKeyColumn(2));
            model.setResult(DataService.getInstance().executeQuery(connection, "PUBLIC", "SELECT ID FROM UNKEYED"));
            assertFalse(model.isPrimaryKeyColumn(0), "A later unkeyed query must clear key highlighting");
        }
    }

    @Test void unavailableKeyMetadataDoesNotPreventDisplayingResults() {
        Connection unsupported = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> { throw new SQLFeatureNotSupportedException("No metadata"); });
        assertEquals(Set.of(), DataService.primaryKeyColumns(unsupported,
                List.of(new DataService.ResultColumn(null, "PUBLIC", "KEYED", "ID"))));
    }
}
