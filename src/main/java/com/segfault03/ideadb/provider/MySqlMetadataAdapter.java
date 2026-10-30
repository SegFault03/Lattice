package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class MySqlMetadataAdapter extends JdbcMetadataAdapter {
    protected DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(selected, null); }
    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = conn.createStatement(); ResultSet rows = statement.executeQuery("SHOW DATABASES;")) {
            while (rows.next()) names.add(rows.getString(1));
        } catch (SQLException failure) {
            names.clear();
            try (ResultSet rows = conn.getMetaData().getCatalogs()) { while (rows.next()) names.add(rows.getString("TABLE_CAT")); }
        }
        Collections.sort(names); return names;
    }
    protected void enrichColumns(Connection conn, ConnectionConfig config, String database, String schema, String table, List<ColumnMetadata> columns) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        if (meta.getDatabaseMajorVersion() > 5 || meta.getDatabaseMajorVersion() == 5 && meta.getDatabaseMinorVersion() >= 6)
            enrichTemporalPrecision(conn, database == null ? conn.getCatalog() : database, table, columns);
        if (meta.getDatabaseMajorVersion() > 5 || meta.getDatabaseMajorVersion() == 5 && meta.getDatabaseMinorVersion() >= 7)
            enrichGenerated(conn, database == null ? conn.getCatalog() : database, table, columns);
    }
    // Connector/J's IS_GENERATEDCOLUMN varies by driver version; EXTRA is authoritative ("DEFAULT_GENERATED" is only an expression default).
    private static void enrichGenerated(Connection conn, String database, String table, List<ColumnMetadata> columns) throws SQLException {
        Set<String> generated = new HashSet<>();
        try (var statement = conn.prepareStatement("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND (EXTRA LIKE '%VIRTUAL GENERATED%' OR EXTRA LIKE '%STORED GENERATED%')")) {
            statement.setString(1, database); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) generated.add(rows.getString(1)); }
        }
        columns.replaceAll(c -> c.isGenerated() || !generated.contains(c.getName()) ? c : new ColumnMetadata(c.getName(), c.getTypeName(), c.getDataType(), c.getColumnSize(), c.getDecimalDigits(), c.isNullable(), c.isPrimaryKey(), c.isAutoIncrement(), c.getDefaultValue(), true));
    }
}
