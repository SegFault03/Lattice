package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class HsqlMetadataAdapter extends JdbcMetadataAdapter {
    protected DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(null, selected); }
    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet rows = conn.getMetaData().getSchemas()) {
            while (rows.next()) { String name = rows.getString("TABLE_SCHEM"); if (!names.contains(name)) names.add(name); }
        }
        if (names.isEmpty()) names.add("PUBLIC");
        Collections.sort(names); return names;
    }
    public String currentSchema(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement(); ResultSet rows = statement.executeQuery("VALUES(CURRENT_SCHEMA)")) {
            if (!rows.next()) throw new SQLException("Current schema unavailable");
            return rows.getString(1);
        }
    }
    protected void enrichColumns(Connection conn, ConnectionConfig config, String database, String schema, String table, List<ColumnMetadata> columns) throws Exception {
        enrichTemporalPrecision(conn, schema == null ? currentSchema(conn) : schema, table, columns);
    }
}
