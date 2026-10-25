package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

/** Shared SQL construction only. Every engine-sensitive operation is supplied by a dialect. */
abstract class AbstractSqlDialect implements SqlDialect {
    protected abstract String identifierQuote();
    protected abstract void appendColumn(StringBuilder sql, ColumnDefinition column);
    protected abstract void appendMetadataColumn(StringBuilder sql, ColumnMetadata column);
    protected String tableSuffix() { return ";"; }
    protected String literalDefault(String raw) { return "'" + raw.replace("'", "''") + "'"; }
    public String quoteIdentifier(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Identifier cannot be empty");
        String quote = identifierQuote();
        return quote + name.replace(quote, quote + quote) + quote;
    }
    public String formatTable(DatabaseNamespace namespace, String table) {
        List<String> parts = new ArrayList<>();
        if (namespace.catalog() != null && !namespace.catalog().isEmpty()) parts.add(quoteIdentifier(namespace.catalog()));
        if (namespace.schema() != null && !namespace.schema().isEmpty()) parts.add(quoteIdentifier(namespace.schema()));
        parts.add(quoteIdentifier(table));
        return String.join(".", parts);
    }
    protected String quoteIdentifier(ConnectionConfig config, String name) { return quoteIdentifier(name); }
    protected String formatTable(ConnectionConfig config, String selected, String table) {
        return formatTable(DatabaseProviders.get(config.getType()).namespace(selected), table);
    }
    public String paginate(String sql, int limit, int offset) {
        if (limit < 0 || offset < 0) throw new IllegalArgumentException("Page limit/offset must not be negative");
        return limit > 0 ? sql + " LIMIT " + limit + (offset > 0 ? " OFFSET " + offset : "") : sql;
    }
    protected void executeSql(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.setQueryTimeout(60); statement.execute(sql); }
    }
    public void createTable(Connection conn, ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) throws Exception {
        String sql = buildCreateTableSql(config, dbName, tableName, columns);
        executeSql(conn, sql);
    }

    public void alterTableDropColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, String colName) throws Exception {
        executeSql(conn, buildDropColumnSql(config, dbName, tableName, colName));
    }

    public String buildAddColumnSql(ConnectionConfig config, String dbName, String table, ColumnDefinition column) {
        var sql = new StringBuilder("ALTER TABLE " + formatTable(config, dbName, table) + " ADD COLUMN ");
        appendColumn(sql, column);
        return sql + ";";
    }
    public String buildDropColumnSql(ConnectionConfig config, String dbName, String table, String column) {
        return "ALTER TABLE " + formatTable(config, dbName, table) + " DROP COLUMN " + quoteIdentifier(column) + ";";
    }
    public String buildRenameColumnSql(Connection conn, ConnectionConfig config, String dbName, String table, String oldName, String newName) throws Exception {
        return "ALTER TABLE " + formatTable(config, dbName, table) + " RENAME COLUMN " + quoteIdentifier(oldName) + " TO " + quoteIdentifier(newName) + ";";
    }
    public String buildRenameTableSql(ConnectionConfig config, String dbName, String oldName, String newName) {
        return "ALTER TABLE " + formatTable(config, dbName, oldName) + " RENAME TO " + quoteIdentifier(newName) + ";";
    }

    public String buildCreateTableSql(ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) {
        StringBuilder sb = new StringBuilder();

        sb.append("CREATE TABLE ").append(formatTable(config,dbName,tableName)).append(" (\n");

        List<String> pkCols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition col = columns.get(i);
            sb.append("  ");
            appendColumn(sb, col);

            if (col.isPrimaryKey()) {
                pkCols.add(col.getName());
            }

            if (i < columns.size() - 1 || !pkCols.isEmpty()) {
                sb.append(",\n");
            }
        }

        if (!pkCols.isEmpty()) {
            sb.append("  PRIMARY KEY (");
            for (int i = 0; i < pkCols.size(); i++) {
                sb.append(quoteIdentifier(config,pkCols.get(i)));
                if (i < pkCols.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")\n");
        }

        sb.append(")");
        sb.append(tableSuffix());

        return sb.toString();
    }

    public String buildSelectSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (columns == null || columns.isEmpty()) {
            sb.append("*");
        } else {
            for (int i = 0; i < columns.size(); i++) {
                sb.append(quoteIdentifier(config,columns.get(i)));
                if (i < columns.size() - 1) sb.append(", ");
            }
        }
        sb.append(" FROM ");
        sb.append(formatTable(config, dbName, tableName));
        return paginate(sb.toString(), 100, 0) + ";";
    }

    public String buildInsertSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(formatTable(config, dbName, tableName)).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            sb.append(quoteIdentifier(config,columns.get(i)));
            if (i < columns.size() - 1) sb.append(", ");
        }
        sb.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            sb.append("?");
            if (i < columns.size() - 1) sb.append(", ");
        }
        sb.append(");");
        return sb.toString();
    }

    public String buildUpdateSql(ConnectionConfig config, String dbName, String tableName, List<String> columns, List<String> pkColumns) {
        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(formatTable(config, dbName, tableName)).append(" SET ");

        for (int i = 0; i < columns.size(); i++) {
            sb.append(quoteIdentifier(config,columns.get(i))).append(" = ?");
            if (i < columns.size() - 1) sb.append(", ");
        }

        if (pkColumns != null && !pkColumns.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < pkColumns.size(); i++) {
                sb.append(quoteIdentifier(config,pkColumns.get(i))).append(" = ?");
                if (i < pkColumns.size() - 1) sb.append(" AND ");
            }
        }
        sb.append(";");
        return sb.toString();
    }

    public String buildDeleteSql(ConnectionConfig config, String dbName, String tableName, List<String> pkColumns) {
        StringBuilder sb = new StringBuilder("DELETE FROM ");
        sb.append(formatTable(config, dbName, tableName));

        if (pkColumns != null && !pkColumns.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < pkColumns.size(); i++) {
                sb.append(quoteIdentifier(config,pkColumns.get(i))).append(" = ?");
                if (i < pkColumns.size() - 1) sb.append(" AND ");
            }
        }
        sb.append(";");
        return sb.toString();
    }

    public String generateCreateTableSql(ConnectionConfig config, String dbName, TableMetadata tableMetadata) {
        StringBuilder sb = new StringBuilder();

        sb.append("-- Partial reconstruction: only columns and primary keys; other constraints, indexes and identity state are omitted.\nCREATE TABLE ");
        sb.append(formatTable(config,dbName,tableMetadata.getName())).append(" (\n");

        List<ColumnMetadata> cols = tableMetadata.getColumns();
        List<String> pkCols = new ArrayList<>();

        for (int i = 0; i < cols.size(); i++) {
            ColumnMetadata col = cols.get(i);
            sb.append("  ");
            appendMetadataColumn(sb, col);

            if (col.isPrimaryKey()) {
                pkCols.add(col.getName());
            }

            if (i < cols.size() - 1 || !pkCols.isEmpty()) {
                sb.append(",\n");
            } else {
                sb.append("\n");
            }
        }

        if (!pkCols.isEmpty()) {
            sb.append("  PRIMARY KEY (");
            for (int i = 0; i < pkCols.size(); i++) {
                sb.append(quoteIdentifier(config,pkCols.get(i)));
                if (i < pkCols.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")\n");
        }

        sb.append(")");
        sb.append(tableSuffix());

        return sb.toString();
    }

    protected boolean needsSize(String type) {
        String t = type.toUpperCase(java.util.Locale.ROOT);
        return t.contains("CHAR") || t.contains("VARCHAR") || t.contains("VARBINARY") || t.contains("BINARY");
    }

    protected static String ddlType(ColumnMetadata column) {
        String type=column.getTypeName();
        if(type.contains("(")) return type;
        return switch(column.getDataType()) {
            case java.sql.Types.CHAR,java.sql.Types.VARCHAR,java.sql.Types.NCHAR,java.sql.Types.NVARCHAR,java.sql.Types.BINARY,java.sql.Types.VARBINARY -> type + "(" + Math.max(1,column.getColumnSize()) + ")";
            case java.sql.Types.NUMERIC,java.sql.Types.DECIMAL -> type + "(" + column.getColumnSize() + "," + column.getDecimalDigits() + ")";
            default -> type;
        };
    }

    protected String formatDefault(ColumnDefinition column) {
        String raw=column.getDefaultValue(), value=raw.trim();
        ColumnDefinition.DefaultKind kind=column.getDefaultKind();
        if (kind == ColumnDefinition.DefaultKind.PRESERVED) return raw;
        if (kind==ColumnDefinition.DefaultKind.AUTO && value.startsWith("SQL:")) { kind=ColumnDefinition.DefaultKind.EXPRESSION; value=value.substring(4).trim(); }
        if (kind==ColumnDefinition.DefaultKind.AUTO && value.startsWith("TEXT:")) { kind=ColumnDefinition.DefaultKind.LITERAL; raw=value.substring(5); value=raw; }
        if (kind==ColumnDefinition.DefaultKind.EXPRESSION) {
            if (value.isEmpty() || value.contains(";") || value.contains("--") || value.contains("/*")) throw new IllegalArgumentException("Enter one SQL default expression");
            return value;
        }
        if (kind==ColumnDefinition.DefaultKind.AUTO) {
            String type=column.getType().toUpperCase(java.util.Locale.ROOT);
            if ((type.contains("TIME") || type.equals("DATE")) && value.matches("(?i)CURRENT_(TIMESTAMP|TIME|DATE)(\\(\\d*\\))?")) return value;
            if (value.equalsIgnoreCase("NULL") || value.equalsIgnoreCase("TRUE") || value.equalsIgnoreCase("FALSE") || value.matches("[+-]?\\d+(\\.\\d+)?")) return value;
            if (value.startsWith("'") && value.endsWith("'")) raw=value.substring(1,value.length()-1).replace("''","'");
        }
        return literalDefault(raw);
    }

}
