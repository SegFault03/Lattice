package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

public interface SqlDialect {
    void createDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception;
    void dropDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception;
    void createTable(Connection conn, ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) throws Exception;
    void dropTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception;
    default void dropForeignTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        throw new UnsupportedOperationException("Foreign tables are not supported by " + config.getType().getDisplayName());
    }
    void truncateTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception;
    void alterTableAddColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, ColumnDefinition col) throws Exception;
    void alterTableDropColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, String colName) throws Exception;
    void alterTableRenameColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       String oldColName, String newColName) throws Exception;
    void alterTableModifyColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       ColumnDefinition col) throws Exception;
    TableMetadata alterTableRename(Connection conn, ConnectionConfig config, String dbName, String oldName, String newName) throws Exception;
    String buildAddColumnSql(ConnectionConfig config, String dbName, String table, ColumnDefinition column);
    String buildDropColumnSql(ConnectionConfig config, String dbName, String table, String column);
    String buildRenameColumnSql(Connection conn, ConnectionConfig config, String dbName, String table, String oldName, String newName) throws Exception;
    default String buildRenameColumnSql(Connection conn, ConnectionConfig config, String dbName, String table,
                                       String oldName, String newName, ColumnDefinition modified) throws Exception {
        return buildRenameColumnSql(conn, config, dbName, table, oldName, newName);
    }
    List<String> buildModifyColumnSql(Connection conn, ConnectionConfig config, String dbName, String table, ColumnDefinition column) throws Exception;
    String buildRenameTableSql(ConnectionConfig config, String dbName, String oldName, String newName);
    String buildCreateTableSql(ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns);
    String buildSelectSql(ConnectionConfig config, String dbName, String tableName, List<String> columns);
    String buildInsertSql(ConnectionConfig config, String dbName, String tableName, List<String> columns);
    String buildUpdateSql(ConnectionConfig config, String dbName, String tableName, List<String> columns, List<String> pkColumns);
    String buildDeleteSql(ConnectionConfig config, String dbName, String tableName, List<String> pkColumns);
    String getCreateTableStatement(Connection conn, ConnectionConfig config, String dbName, TableMetadata tableMetadata) throws Exception;
    String generateCreateTableSql(ConnectionConfig config, String dbName, TableMetadata tableMetadata);
    String quoteIdentifier(String name);
    String formatTable(DatabaseNamespace namespace, String table);
    String paginate(String sql, int limit, int offset);
    String defaultInsert(String target);
}
