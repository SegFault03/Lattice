package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class PostgresMetadataAdapter extends JdbcMetadataAdapter {
    protected DatabaseNamespace namespace(String selected) { return new DatabaseNamespace(null, selected); }
    public List<String> getDatabases(Connection conn, ConnectionConfig config) throws SQLException {
        var names = new ArrayList<String>();
        try (var rows = conn.getMetaData().getSchemas()) { while (rows.next()) names.add(rows.getString("TABLE_SCHEM")); }
        Collections.sort(names); return names;
    }
    public String currentSchema(Connection conn) throws SQLException {
        try (var statement = conn.createStatement(); var rows = statement.executeQuery("SELECT current_schema()")) { rows.next(); return rows.getString(1); }
    }
    protected void enrichColumns(Connection conn, ConnectionConfig config, String selected, String schema, String table, List<ColumnMetadata> columns) throws SQLException {
        String generated = conn.getMetaData().getDatabaseMajorVersion() >= 12 ? "a.attgenerated" : "''";
        Map<String, String[]> details = new HashMap<>();
        try (var statement = conn.prepareStatement("SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod)," + generated + " FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=COALESCE(?,current_schema()) AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped")) {
            statement.setString(1, schema); statement.setString(2, table);
            try (var rows = statement.executeQuery()) { while (rows.next()) details.put(rows.getString(1), new String[]{rows.getString(2), rows.getString(3)}); }
        }
        columns.replaceAll(c -> {
            var detail = details.get(c.getName());
            String type = switch (c.getTypeName()) {
                case "int2" -> "SMALLINT"; case "int4" -> "INTEGER"; case "int8" -> "BIGINT";
                case "float4" -> "REAL"; case "float8" -> "DOUBLE PRECISION"; case "bool" -> "BOOLEAN";
                case "bpchar" -> "CHAR"; case "varchar" -> "VARCHAR";
                case "timestamptz" -> "TIMESTAMP WITH TIME ZONE"; case "timetz" -> "TIME WITH TIME ZONE";
                default -> c.getTypeName();
            };
            boolean unconstrained = detail != null && detail[0].equals("numeric");
            return new ColumnMetadata(c.getName(), type, c.getDataType(), unconstrained || type.equals("bytea") ? 0 : c.getColumnSize(), unconstrained ? Integer.MAX_VALUE : c.getDecimalDigits(), c.isNullable(), c.isPrimaryKey(), c.isAutoIncrement(), c.getDefaultValue(), detail != null && !detail[1].isEmpty());
        });
    }
}
