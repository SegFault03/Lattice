package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.MetadataService;
import java.sql.*;
import java.util.*;

final class PostgresDialect extends AbstractSqlDialect {
    protected String identifierQuote() { return "\""; }
    public String defaultInsert(String target) { return "INSERT INTO " + target + " DEFAULT VALUES"; }
    protected String literalDefault(String raw) { return PostgresProvider.textLiteral(raw); }
    private String type(ColumnDefinition column) {
        String name = switch (column.getType().toUpperCase(Locale.ROOT)) {
            case "DOUBLE" -> "DOUBLE PRECISION";
            case "BLOB", "BINARY", "VARBINARY" -> "BYTEA";
            case "TINYINT" -> "SMALLINT";
            default -> column.getType();
        };
        if (!name.contains("(")) {
            if (column.getSize() > 0 && needsSize(name)) name += "(" + column.getSize() + ")";
            else if (column.getSize() > 0 && (name.equalsIgnoreCase("NUMERIC") || name.equalsIgnoreCase("DECIMAL")))
                name += "(" + column.getSize() + "," + Math.max(0, column.getDecimalDigits()) + ")";
        }
        return name;
    }
    protected void appendColumn(StringBuilder sql, ColumnDefinition column) {
        sql.append(quoteIdentifier(column.getName())).append(' ');
        if (column.isAutoIncrement()) {
            String serial = switch (column.getType().toUpperCase(Locale.ROOT)) {
                case "INT", "INTEGER", "SERIAL" -> "SERIAL";
                case "BIGINT", "BIGSERIAL" -> "BIGSERIAL";
                default -> throw new IllegalArgumentException("Auto-generated PostgreSQL columns require INTEGER or BIGINT");
            };
            if (!Objects.toString(column.getDefaultValue(), "").isBlank()) throw new IllegalArgumentException("An auto-generated column cannot have another default");
            sql.append(serial);
        } else {
            sql.append(type(column));
            if (!Objects.toString(column.getDefaultValue(), "").isBlank()) sql.append(" DEFAULT ").append(formatDefault(column));
        }
        if (!column.isNullable()) sql.append(" NOT NULL");
    }
    protected void appendMetadataColumn(StringBuilder sql, ColumnMetadata column) {
        sql.append(quoteIdentifier(column.getName())).append(' ').append(ddlType(column));
        if (column.getDefaultValue() != null) sql.append(" DEFAULT ").append(column.getDefaultValue());
        if (!column.isNullable()) sql.append(" NOT NULL");
    }
    public void createDatabase(Connection c, ConnectionConfig config, String schema) throws Exception { executeSql(c, "CREATE SCHEMA " + quoteIdentifier(schema)); }
    public void dropDatabase(Connection c, ConnectionConfig config, String schema) throws Exception { executeSql(c, "DROP SCHEMA " + quoteIdentifier(schema) + " CASCADE"); }
    public void dropTable(Connection c, ConnectionConfig config, String schema, String table) throws Exception { executeSql(c, "DROP TABLE " + formatTable(config,schema,table)); }
    public String buildDropForeignTableSql(ConnectionConfig config, String schema, String table) { return "DROP FOREIGN TABLE " + formatTable(config, schema, table); }
    @Override public void dropForeignTable(Connection c, ConnectionConfig config, String schema, String table) throws Exception { executeSql(c, buildDropForeignTableSql(config, schema, table)); }
    public void truncateTable(Connection c, ConnectionConfig config, String schema, String table) throws Exception { executeSql(c, "TRUNCATE TABLE " + formatTable(config,schema,table)); }
    public void alterTableAddColumn(Connection c, ConnectionConfig config, String schema, String table, ColumnDefinition column) throws Exception {
        executeSql(c, buildAddColumnSql(config, schema, table, column));
    }

    public String buildRenameColumnSql(Connection c, ConnectionConfig config, String schema, String table, String oldName, String newName) throws Exception {
        return "ALTER TABLE " + formatTable(config, schema, table) + " RENAME COLUMN " + quoteIdentifier(oldName) + " TO " + quoteIdentifier(newName) + ";";
    }

    public void alterTableRenameColumn(Connection c, ConnectionConfig config, String schema, String table, String oldName, String newName) throws Exception {
        executeSql(c, buildRenameColumnSql(c, config, schema, table, oldName, newName));
    }

    public TableMetadata alterTableRename(Connection c, ConnectionConfig config, String schema, String table, String newName) throws Exception {
        executeSql(c, buildRenameTableSql(config, schema, table, newName));
        var renamed = MetadataService.getInstance().getTables(c,config,schema).stream().filter(t -> t.getName().equals(newName)).findFirst().orElseThrow();
        renamed.setColumns(MetadataService.getInstance().getColumns(c,config,schema,newName)); return renamed;
    }
    public List<String> buildModifyColumnSql(Connection c, ConnectionConfig config, String schema, String table, ColumnDefinition requested) throws Exception {
        var original = MetadataService.getInstance().getColumns(c,config,schema,table).stream().filter(col -> col.getName().equals(requested.getName())).findFirst().orElseThrow();
        if (original.isGenerated()) throw new IllegalArgumentException("Change a generated expression in the SQL console");
        String prefix = "ALTER TABLE " + formatTable(config,schema,table) + " ALTER COLUMN " + quoteIdentifier(requested.getName());
        boolean identity = original.isAutoIncrement() && original.getDefaultValue() == null;
        if (identity && !requested.getDefaultValue().isBlank()) throw new IllegalArgumentException("An identity column cannot have another default");
        List<String> statements = new ArrayList<>();
        if (!identity) statements.add(prefix + " DROP DEFAULT;");
        statements.add(prefix + " TYPE " + type(requested) + " USING " + quoteIdentifier(requested.getName()) + "::" + type(requested) + ";");
        if (!identity && !requested.getDefaultValue().isBlank()) {
            String value = Objects.equals(requested.getDefaultValue(),original.getDefaultValue()) ? original.getDefaultValue() : formatDefault(requested);
            statements.add(prefix + " SET DEFAULT " + value + ";");
        }
        statements.add(prefix + (requested.isNullable() ? " DROP NOT NULL;" : " SET NOT NULL;"));
        return List.copyOf(statements);
    }

    public void alterTableModifyColumn(Connection c, ConnectionConfig config, String schema, String table, ColumnDefinition requested) throws Exception {
        List<String> statements = buildModifyColumnSql(c, config, schema, table, requested);
        com.segfault03.ideadb.service.DatabaseSession.Operation<Void> change = connection -> {
            for (String sql : statements) executeSql(connection, sql);
            return null;
        };
        if (c.getAutoCommit()) com.segfault03.ideadb.service.DataService.getInstance().inTransaction(c,change);
        else {
            Savepoint savepoint = c.setSavepoint();
            try { change.run(c); c.releaseSavepoint(savepoint); }
            catch (Exception failure) {
                try { c.rollback(savepoint); c.releaseSavepoint(savepoint); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); try { c.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); } }
                throw failure;
            }
        }
    }

    public String getCreateTableStatement(Connection c, ConnectionConfig config, String schema, TableMetadata table) throws Exception {
        return PostgresCreateStatement.read(c, schema == null || schema.isBlank() ? DatabaseProviders.get(config.getType()).metadata().currentSchema(c) : schema, table.getName(), this);
    }
}
