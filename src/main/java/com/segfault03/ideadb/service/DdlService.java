package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.provider.*;
import java.sql.Connection;
import java.util.List;

public final class DdlService {
    private static final DdlService INSTANCE = new DdlService();
    private DdlService() {}
    public static DdlService getInstance() { return INSTANCE; }
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
