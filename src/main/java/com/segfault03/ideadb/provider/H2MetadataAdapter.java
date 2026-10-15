package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class H2MetadataAdapter extends JdbcMetadataAdapter {
    protected DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(null, selected); }
    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws SQLException {
        var names = new ArrayList<String>();
        try (var rows = conn.getMetaData().getSchemas()) { while (rows.next()) names.add(rows.getString("TABLE_SCHEM")); }
        Collections.sort(names); return names;
    }
    public List<TableMetadata> getTables(Connection conn, ConnectionConfig config, String schema) throws Exception {
        return super.getTables(conn, config, schema == null || schema.isBlank() ? currentSchema(conn) : schema);
    }
    public List<ColumnMetadata> getColumns(Connection conn, ConnectionConfig config, String schema, String table) throws Exception {
        return super.getColumns(conn, config, schema == null || schema.isBlank() ? currentSchema(conn) : schema, table);
    }
    protected void enrichColumns(Connection conn, ConnectionConfig config, String selected, String schema, String table, List<ColumnMetadata> columns) throws Exception {
        enrichTemporalPrecision(conn, schema, table, columns);
        Set<String> generated = new HashSet<>();
        try (var statement = conn.prepareStatement("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND IS_GENERATED='ALWAYS'")) {
            statement.setString(1, schema); statement.setString(2, table);
            try (var rows = statement.executeQuery()) { while (rows.next()) generated.add(rows.getString(1)); }
        }
        columns.replaceAll(c -> new ColumnMetadata(c.getName(), c.getTypeName(), c.getDataType(), c.getColumnSize(), c.getDecimalDigits(), c.isNullable(), c.isPrimaryKey(), c.isAutoIncrement(), c.getDefaultValue(), generated.contains(c.getName())));
    }
}
