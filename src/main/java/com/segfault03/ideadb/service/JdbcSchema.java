package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import java.sql.*;

/** Older HSQLDB setSchema implementations incorrectly execute SET SCHEMA as a query. */
final class JdbcSchema {
    private JdbcSchema() {}
    static String current(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("VALUES(CURRENT_SCHEMA)")) {
            if (!rows.next()) throw new SQLException("Current schema unavailable");
            return rows.getString(1);
        }
    }
    static void select(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA " + DdlService.quoteIdentifier(DatabaseType.HSQLDB, schema));
        }
    }
}
