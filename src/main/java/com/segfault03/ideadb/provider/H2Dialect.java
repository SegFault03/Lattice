package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.MetadataService;
import java.sql.*;
import java.util.*;

final class H2Dialect extends AbstractSqlDialect {
    protected String identifierQuote() { return "\""; }
    public String defaultInsert(String target) { return "INSERT INTO " + target + " DEFAULT VALUES"; }
    public void createDatabase(Connection conn, ConnectionConfig config, String name) throws Exception {
        executeSql(conn, "CREATE SCHEMA " + quoteIdentifier(name));
    }
    public void dropDatabase(Connection conn, ConnectionConfig config, String name) throws Exception {
        executeSql(conn, "DROP SCHEMA " + quoteIdentifier(name) + " CASCADE");
    }
    public void dropTable(Connection conn, ConnectionConfig config, String schema, String table) throws Exception {
        executeSql(conn, "DROP TABLE " + formatTable(config, schema, table) + " RESTRICT");
    }
    public void truncateTable(Connection conn, ConnectionConfig config, String schema, String table) throws Exception {
        executeSql(conn, "TRUNCATE TABLE " + formatTable(config, schema, table));
    }
    public void alterTableAddColumn(Connection conn, ConnectionConfig config, String schema, String table, ColumnDefinition column) throws Exception {
        executeSql(conn, buildAddColumnSql(config, schema, table, column));
    }

    public String buildRenameColumnSql(Connection conn, ConnectionConfig config, String schema, String table, String oldName, String newName) throws Exception {
        return "ALTER TABLE " + formatTable(config, schema, table) + " ALTER COLUMN " + quoteIdentifier(oldName) + " RENAME TO " + quoteIdentifier(newName) + ";";
    }

    public void alterTableRenameColumn(Connection conn, ConnectionConfig config, String schema, String table, String oldName, String newName) throws Exception {
        executeSql(conn, buildRenameColumnSql(conn, config, schema, table, oldName, newName));
    }

    public List<String> buildModifyColumnSql(Connection conn, ConnectionConfig config, String schema, String table, ColumnDefinition column) throws Exception {
        ColumnMetadata original = MetadataService.getInstance().getColumns(conn, config, schema, table).stream()
                .filter(c -> c.getName().equals(column.getName())).findFirst().orElseThrow();
        if (original.isGenerated()) throw new IllegalArgumentException("Change a generated expression in the SQL console");
        if (original.isPrimaryKey() && column.isNullable()) throw new IllegalArgumentException("A primary-key column cannot allow NULL");
        String requestedDefault = Objects.toString(column.getDefaultValue(), "");
        if (original.isAutoIncrement() && !requestedDefault.isBlank())
            throw new IllegalArgumentException("An identity column cannot have another default");
        String prefix = "ALTER TABLE " + formatTable(config, schema, table) + " ALTER COLUMN " + quoteIdentifier(column.getName());
        List<String> statements = new ArrayList<>();
        String beforeDefault = Objects.toString(original.getDefaultValue(), "");
        if (!original.isAutoIncrement() && !requestedDefault.equals(beforeDefault))
            statements.add(prefix + (requestedDefault.isBlank() ? " DROP DEFAULT;" : " SET DEFAULT " + formatDefault(column) + ";"));
        String type = columnType(column);
        String beforeType = ddlType(original);
        if (beforeType.matches("(?i)(?:TIMESTAMP|TIME)(?: WITH(?:OUT)? TIME ZONE)?"))
            beforeType = beforeType.replaceFirst("(?i)^(TIMESTAMP|TIME)", "$1(" + original.getDecimalDigits() + ")");
        if (!type.equalsIgnoreCase(beforeType)) statements.add(prefix + " SET DATA TYPE " + type + ";");
        if (column.isNullable() != original.isNullable())
            statements.add(prefix + (column.isNullable() ? " DROP NOT NULL;" : " SET NOT NULL;"));
        // Targeted ALTERs retain ON UPDATE, comments, identity mode and sequence options/state.
        return List.copyOf(statements);
    }
    private String columnType(ColumnDefinition column) {
        String type = column.getType();
        if (column.getSize() > 0 && !type.contains("(")) {
            if (needsSize(type)) type += "(" + column.getSize() + ")";
            else if (type.equalsIgnoreCase("DECIMAL") || type.equalsIgnoreCase("NUMERIC"))
                type += "(" + column.getSize() + "," + Math.max(0, column.getDecimalDigits()) + ")";
        }
        return type;
    }
    public void alterTableModifyColumn(Connection conn, ConnectionConfig config, String schema, String table, ColumnDefinition column) throws Exception {
        for (String sql : buildModifyColumnSql(conn, config, schema, table, column)) executeSql(conn, sql);
    }

    public TableMetadata alterTableRename(Connection conn, ConnectionConfig config, String schema, String oldName, String newName) throws Exception {
        executeSql(conn, buildRenameTableSql(config, schema, oldName, newName));
        var renamed = MetadataService.getInstance().getTables(conn, config, schema).stream().filter(t -> t.getName().equals(newName)).findFirst().orElseThrow();
        renamed.setColumns(MetadataService.getInstance().getColumns(conn, config, schema, newName));
        return renamed;
    }
    public String getCreateTableStatement(Connection conn, ConnectionConfig config, String schema, TableMetadata table) throws Exception {
        String target = formatTable(config, schema == null || schema.isBlank() ? conn.getSchema() : schema, table.getName());
        var statements = new ArrayList<String>();
        try (var statement = conn.createStatement(); var rows = statement.executeQuery("SCRIPT NODATA TABLE " + target)) {
            while (rows.next()) {
                String sql = rows.getString(1);
                if (sql.contains(target) && (sql.startsWith("CREATE ") || sql.startsWith("ALTER TABLE ") || sql.startsWith("COMMENT ON "))) statements.add(sql);
            }
        }
        if (statements.isEmpty()) throw new SQLException("No CREATE statement found for " + table.getName());
        return "-- Referenced schemas, tables, sequences and user-defined types must already exist.\n" + String.join("\n", statements);
    }
    protected void appendColumn(StringBuilder sql, ColumnDefinition column) {
        sql.append(quoteIdentifier(column.getName())).append(" ").append(column.getType());
        if (column.getSize() > 0 && !column.getType().contains("(")) {
            if (needsSize(column.getType())) sql.append("(").append(column.getSize()).append(")");
            else if (column.getType().equalsIgnoreCase("DECIMAL") || column.getType().equalsIgnoreCase("NUMERIC"))
                sql.append("(").append(column.getSize()).append(",").append(Math.max(0, column.getDecimalDigits())).append(")");
        }
        if (column.isAutoIncrement()) sql.append(" GENERATED BY DEFAULT AS IDENTITY");
        else if (column.getDefaultValue() != null && !column.getDefaultValue().isBlank()) sql.append(" DEFAULT ").append(formatDefault(column));
        if (!column.isNullable()) sql.append(" NOT NULL");
    }
    protected void appendMetadataColumn(StringBuilder sql, ColumnMetadata column) {
        sql.append(quoteIdentifier(column.getName())).append(" ").append(ddlType(column));
        if (column.isAutoIncrement()) sql.append(" GENERATED BY DEFAULT AS IDENTITY");
        else if (column.getDefaultValue() != null && !column.getDefaultValue().isBlank()) sql.append(" DEFAULT ").append(column.getDefaultValue());
        if (!column.isNullable()) sql.append(" NOT NULL");
    }
}
