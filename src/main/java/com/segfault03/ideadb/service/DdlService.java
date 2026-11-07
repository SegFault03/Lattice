package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.provider.*;
import java.sql.Connection;
import java.util.List;

public final class DdlService {
    private static final DdlService INSTANCE = new DdlService();
    private DdlService() {}
    public static DdlService getInstance() { return INSTANCE; }
    /** Preview the complete batch against the unchanged live schema. No DDL is issued here. */
    public List<String> previewAlterBatch(Connection conn, ConnectionConfig config, String dbName, TableAlterationBatch batch) throws Exception {
        String problem = batch.problem();
        if (problem != null) throw new IllegalArgumentException(problem);
        var dialect = DatabaseProviders.get(config.getType()).dialect();
        List<String> statements = new java.util.ArrayList<>();
        for (TableAlteration change : batch.orderedChanges()) {
            DatabaseProviders.get(config.getType()).requireFeature(change.feature());
            if (change.kind() == TableAlteration.Kind.RENAME_COLUMN) {
                ColumnDefinition modified = batch.changes().stream().filter(c -> c.kind() == TableAlteration.Kind.MODIFY
                        && c.original().getName().equals(change.original().getName())).map(TableAlteration::definition).findFirst().orElse(null);
                statements.add(dialect.buildRenameColumnSql(conn, config, dbName, batch.tableName(), change.original().getName(), change.name(), modified));
            } else statements.addAll(previewAlter(conn, config, dbName, batch.tableName(), change));
        }
        return List.copyOf(statements);
    }

    public static final class BatchFailure extends Exception {
        private final List<TableAlteration> completed;
        public BatchFailure(Exception cause, List<TableAlteration> completed) {
            super(cause.getMessage(), cause); this.completed = List.copyOf(completed);
        }
        public List<TableAlteration> completed() { return completed; }
    }

    /** Preflight the whole snapshot before the first write, then stop at the first failed operation. */
    public void applyAlterBatch(Connection conn, ConnectionConfig config, String dbName, TableAlterationBatch batch, List<String> reviewedSql) throws Exception {
        List<ColumnMetadata> current = MetadataService.getInstance().getColumns(conn, config, dbName, batch.tableName());
        if (current.size() != batch.baseline().size() || batch.baseline().stream().anyMatch(before ->
                !sameDefinition(before, current.stream().filter(c -> c.getName().equals(before.getName())).findFirst().orElse(null)))
                || batch.changes().stream().filter(c -> c.original() != null).anyMatch(change -> !sameDefinition(change.original(),
                    current.stream().filter(c -> c.getName().equals(change.original().getName())).findFirst().orElse(null))))
            throw new java.sql.SQLException("The table definition changed. Review the refreshed columns and SQL before applying again.");
        if (!previewAlterBatch(conn, config, dbName, batch).equals(reviewedSql))
            throw new java.sql.SQLException("The SQL preview changed. Review the updated SQL before applying again.");
        List<TableAlteration> completed = new java.util.ArrayList<>();
        DatabaseSession.Operation<Void> apply = connection -> {
            for (TableAlteration change : batch.orderedChanges()) {
                try { applyUnchecked(connection, config, dbName, batch.tableName(), change); completed.add(change); }
                catch (Exception error) { throw new BatchFailure(error, completed); }
            }
            return null;
        };
        if (config.getType() == DatabaseType.POSTGRESQL) {
            try { DataService.getInstance().inTransaction(conn, apply); }
            catch (Exception error) { throw new BatchFailure(error, List.of()); }
        } else apply.run(conn);
    }
    /** Reads server metadata where required; never executes DDL. Call off the EDT. */
    public List<String> previewAlter(Connection conn, ConnectionConfig config, String dbName, String table, TableAlteration change) throws Exception {
        var provider = DatabaseProviders.get(config.getType());
        provider.requireFeature(change.feature());
        var dialect = provider.dialect();
        return switch (change.kind()) {
            case ADD -> List.of(dialect.buildAddColumnSql(config, dbName, table, change.definition()));
            case MODIFY -> dialect.buildModifyColumnSql(conn, config, dbName, table, change.definition());
            case DROP -> List.of(dialect.buildDropColumnSql(config, dbName, table, change.original().getName()));
            case RENAME_COLUMN -> List.of(dialect.buildRenameColumnSql(conn, config, dbName, table, change.original().getName(), change.name()));
            case RENAME_TABLE -> List.of(dialect.buildRenameTableSql(config, dbName, table, change.name()));
        };
    }
    /** Keeps provider validation, transaction boundaries and metadata refresh in the existing executors. */
    public void applyAlter(Connection conn, ConnectionConfig config, String dbName, String table, TableAlteration change) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(change.feature());
        if (change.original() != null) {
            ColumnMetadata current = MetadataService.getInstance().getColumns(conn, config, dbName, table).stream()
                    .filter(c -> c.getName().equals(change.original().getName())).findFirst().orElse(null);
            if (!sameDefinition(change.original(), current))
                throw new java.sql.SQLException("The column definition changed. Reload and review the current column before applying again.");
        }
        applyUnchecked(conn, config, dbName, table, change);
    }
    private void applyUnchecked(Connection conn, ConnectionConfig config, String dbName, String table, TableAlteration change) throws Exception {
        switch (change.kind()) {
            case ADD -> alterTableAddColumn(conn, config, dbName, table, change.definition());
            case MODIFY -> alterTableModifyColumn(conn, config, dbName, table, change.definition());
            case DROP -> alterTableDropColumn(conn, config, dbName, table, change.original().getName());
            case RENAME_COLUMN -> alterTableRenameColumn(conn, config, dbName, table, change.original().getName(), change.name());
            case RENAME_TABLE -> alterTableRename(conn, config, dbName, table, change.name());
        }
    }
    private static boolean sameDefinition(ColumnMetadata before, ColumnMetadata now) {
        return now != null && before.getTypeName().equals(now.getTypeName()) && before.getDataType() == now.getDataType()
                && before.getColumnSize() == now.getColumnSize() && before.getDecimalDigits() == now.getDecimalDigits()
                && before.isNullable() == now.isNullable() && before.isAutoIncrement() == now.isAutoIncrement()
                && before.isPrimaryKey() == now.isPrimaryKey() && before.isForeignKey() == now.isForeignKey()
                && before.isGenerated() == now.isGenerated() && java.util.Objects.equals(before.getDefaultValue(), now.getDefaultValue());
    }
    public void createDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.CREATE_NAMESPACE);
        DatabaseProviders.get(config.getType()).dialect().createDatabase(conn, config, dbName);
    }
    public void dropDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.DROP_NAMESPACE);
        DatabaseProviders.get(config.getType()).dialect().dropDatabase(conn, config, dbName);
    }
    public void createTable(Connection conn, ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.CREATE_TABLE);
        DatabaseProviders.get(config.getType()).dialect().createTable(conn, config, dbName, tableName, columns);
    }
    public void dropTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.DROP_TABLE);
        DatabaseProviders.get(config.getType()).dialect().dropTable(conn, config, dbName, tableName);
    }
    public void dropForeignTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.DROP_TABLE);
        DatabaseProviders.get(config.getType()).dialect().dropForeignTable(conn, config, dbName, tableName);
    }
    public void truncateTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.TRUNCATE_TABLE);
        DatabaseProviders.get(config.getType()).dialect().truncateTable(conn, config, dbName, tableName);
    }
    public void alterTableAddColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, ColumnDefinition col) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.ADD_COLUMN);
        DatabaseProviders.get(config.getType()).dialect().alterTableAddColumn(conn, config, dbName, tableName, col);
    }
    public void alterTableDropColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, String colName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.DROP_COLUMN);
        DatabaseProviders.get(config.getType()).dialect().alterTableDropColumn(conn, config, dbName, tableName, colName);
    }
    public void alterTableRenameColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       String oldColName, String newColName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.RENAME_COLUMN);
        DatabaseProviders.get(config.getType()).dialect().alterTableRenameColumn(conn, config, dbName, tableName, oldColName, newColName);
    }
    public void alterTableModifyColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       ColumnDefinition col) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.MODIFY_COLUMN);
        DatabaseProviders.get(config.getType()).dialect().alterTableModifyColumn(conn, config, dbName, tableName, col);
    }
    public TableMetadata alterTableRename(Connection conn, ConnectionConfig config, String dbName, String oldName, String newName) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.RENAME_TABLE);
        return DatabaseProviders.get(config.getType()).dialect().alterTableRename(conn, config, dbName, oldName, newName);
    }
    public String buildCreateTableSql(ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) {
        return DatabaseProviders.get(config.getType()).dialect().buildCreateTableSql(config, dbName, tableName, columns);
    }
    public String buildSelectSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        return DatabaseProviders.get(config.getType()).dialect().buildSelectSql(config, dbName, tableName, columns);
    }
    public String buildInsertSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        return DatabaseProviders.get(config.getType()).dialect().buildInsertSql(config, dbName, tableName, columns);
    }
    public String buildUpdateSql(ConnectionConfig config, String dbName, String tableName, List<String> columns, List<String> pkColumns) {
        return DatabaseProviders.get(config.getType()).dialect().buildUpdateSql(config, dbName, tableName, columns, pkColumns);
    }
    public String buildDeleteSql(ConnectionConfig config, String dbName, String tableName, List<String> pkColumns) {
        return DatabaseProviders.get(config.getType()).dialect().buildDeleteSql(config, dbName, tableName, pkColumns);
    }
    public String getCreateTableStatement(Connection conn, ConnectionConfig config, String dbName, TableMetadata tableMetadata) throws Exception {
        DatabaseProviders.get(config.getType()).requireFeature(DatabaseFeature.NATIVE_CREATE_STATEMENT);
        return DatabaseProviders.get(config.getType()).dialect().getCreateTableStatement(conn, config, dbName, tableMetadata);
    }
    public String generateCreateTableSql(ConnectionConfig config, String dbName, TableMetadata tableMetadata) {
        return DatabaseProviders.get(config.getType()).dialect().generateCreateTableSql(config, dbName, tableMetadata);
    }
    public static String quoteIdentifier(ConnectionConfig config, String name) { return quoteIdentifier(config.getType(), name); }
    public static String quoteIdentifier(DatabaseType type, String name) { return DatabaseProviders.get(type).dialect().quoteIdentifier(name); }
    public static String formatTable(ConnectionConfig config, String selected, String table) {
        DatabaseProvider provider = DatabaseProviders.get(config.getType());
        return provider.dialect().formatTable(provider.namespace(selected), table);
    }
}
