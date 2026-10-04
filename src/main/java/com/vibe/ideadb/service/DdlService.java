package com.vibe.ideadb.service;

import com.vibe.ideadb.model.ColumnDefinition;
import com.vibe.ideadb.model.ColumnMetadata;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.DatabaseType;
import com.vibe.ideadb.model.TableMetadata;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DdlService {
    private static final DdlService INSTANCE = new DdlService();

    private DdlService() {
    }

    public static DdlService getInstance() {
        return INSTANCE;
    }

    public void createDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql;
        if (config.getType() == DatabaseType.MYSQL) {
            sql = "CREATE DATABASE `" + dbName + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;";
        } else {
            sql = "CREATE SCHEMA \"" + dbName.toUpperCase() + "\" AUTHORIZATION DBA;";
        }
        executeSql(conn, sql);
    }

    public void dropDatabase(Connection conn, ConnectionConfig config, String dbName) throws Exception {
        String sql;
        if (config.getType() == DatabaseType.MYSQL) {
            sql = "DROP DATABASE `" + dbName + "`;";
        } else {
            sql = "DROP SCHEMA \"" + dbName + "\" CASCADE;";
        }
        executeSql(conn, sql);
    }

    public void createTable(Connection conn, ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) throws Exception {
        String sql = buildCreateTableSql(config, dbName, tableName, columns);
        executeSql(conn, sql);
    }

    public void dropTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql = (config.getType() == DatabaseType.MYSQL)
                ? "DROP TABLE `" + dbName + "`.`" + tableName + "`;"
                : "DROP TABLE " + formatTable(config, dbName, tableName) + " CASCADE;";
        executeSql(conn, sql);
    }

    public void truncateTable(Connection conn, ConnectionConfig config, String dbName, String tableName) throws Exception {
        String sql;
        if (config.getType() == DatabaseType.MYSQL) {
            sql = "TRUNCATE TABLE `" + dbName + "`.`" + tableName + "`;";
        } else {
            sql = "TRUNCATE TABLE " + formatTable(config, dbName, tableName) + " AND COMMIT;";
        }
        executeSql(conn, sql);
    }

    public void alterTableAddColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, ColumnDefinition col) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (config.getType() == DatabaseType.MYSQL) {
            sb.append("ALTER TABLE `").append(dbName).append("`.`").append(tableName).append("` ADD COLUMN ");
            appendMysqlColumnDef(sb, col);
            sb.append(";");
        } else {
            sb.append("ALTER TABLE ").append(formatTable(config, dbName, tableName)).append(" ADD COLUMN ");
            appendHsqlColumnDef(sb, col);
            sb.append(";");
        }
        executeSql(conn, sb.toString());
    }

    public void alterTableDropColumn(Connection conn, ConnectionConfig config, String dbName, String tableName, String colName) throws Exception {
        String sql = (config.getType() == DatabaseType.MYSQL)
                ? "ALTER TABLE `" + dbName + "`.`" + tableName + "` DROP COLUMN `" + colName + "`;"
                : "ALTER TABLE " + formatTable(config, dbName, tableName) + " DROP COLUMN " + colName + ";";
        executeSql(conn, sql);
    }

    public void alterTableRenameColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       String oldColName, String newColName) throws Exception {
        String sql = (config.getType() == DatabaseType.MYSQL)
                ? "ALTER TABLE `" + dbName + "`.`" + tableName + "` RENAME COLUMN `" + oldColName + "` TO `" + newColName + "`;"
                : "ALTER TABLE " + formatTable(config, dbName, tableName) + " ALTER COLUMN " + oldColName + " RENAME TO " + newColName + ";";
        executeSql(conn, sql);
    }

    public void alterTableModifyColumn(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                       ColumnDefinition col) throws Exception {
        StringBuilder sb = new StringBuilder();
        if (config.getType() == DatabaseType.MYSQL) {
            sb.append("ALTER TABLE `").append(dbName).append("`.`").append(tableName).append("` MODIFY COLUMN ");
            appendMysqlColumnDef(sb, col);
            sb.append(";");
        } else {
            sb.append("ALTER TABLE ").append(formatTable(config, dbName, tableName))
                    .append(" ALTER COLUMN ").append(col.getName()).append(" SET DATA TYPE ").append(col.getType());
            if (col.getSize() > 0 && needsSize(col.getType())) {
                sb.append("(").append(col.getSize()).append(")");
            }
            sb.append(";");
        }
        executeSql(conn, sb.toString());
    }

    public void alterTableRename(Connection conn, ConnectionConfig config, String dbName, String oldName, String newName) throws Exception {
        String sql = (config.getType() == DatabaseType.MYSQL)
                ? "RENAME TABLE `" + dbName + "`.`" + oldName + "` TO `" + dbName + "`.`" + newName + "`;"
                : "ALTER TABLE " + formatTable(config, dbName, oldName) + " RENAME TO " + newName + ";";
        executeSql(conn, sql);
    }

    public String buildCreateTableSql(ConnectionConfig config, String dbName, String tableName, List<ColumnDefinition> columns) {
        StringBuilder sb = new StringBuilder();
        boolean isMysql = config.getType() == DatabaseType.MYSQL;

        if (isMysql) {
            sb.append("CREATE TABLE `").append(dbName).append("`.`").append(tableName).append("` (\n");
        } else {
            sb.append("CREATE TABLE ").append(formatTable(config, dbName, tableName)).append(" (\n");
        }

        List<String> pkCols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnDefinition col = columns.get(i);
            sb.append("  ");
            if (isMysql) {
                appendMysqlColumnDef(sb, col);
            } else {
                appendHsqlColumnDef(sb, col);
            }

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
                if (isMysql) {
                    sb.append("`").append(pkCols.get(i)).append("`");
                } else {
                    sb.append(pkCols.get(i));
                }
                if (i < pkCols.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")\n");
        }

        sb.append(")");
        if (isMysql) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        } else {
            sb.append(";");
        }

        return sb.toString();
    }

    public String buildSelectSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("SELECT ");
        if (columns == null || columns.isEmpty()) {
            sb.append("*");
        } else {
            for (int i = 0; i < columns.size(); i++) {
                if (isMysql) sb.append("`").append(columns.get(i)).append("`");
                else sb.append(columns.get(i));
                if (i < columns.size() - 1) sb.append(", ");
            }
        }
        sb.append(" FROM ");
        if (isMysql) {
            sb.append("`").append(dbName).append("`.`").append(tableName).append("` LIMIT 100;");
        } else {
            sb.append(formatTable(config, dbName, tableName)).append(" LIMIT 100;");
        }
        return sb.toString();
    }

    public String buildInsertSql(ConnectionConfig config, String dbName, String tableName, List<String> columns) {
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(formatTable(config, dbName, tableName)).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (isMysql) sb.append("`").append(columns.get(i)).append("`");
            else sb.append(columns.get(i));
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
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(formatTable(config, dbName, tableName)).append(" SET ");

        for (int i = 0; i < columns.size(); i++) {
            if (isMysql) sb.append("`").append(columns.get(i)).append("` = ?");
            else sb.append(columns.get(i)).append(" = ?");
            if (i < columns.size() - 1) sb.append(", ");
        }

        if (pkColumns != null && !pkColumns.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < pkColumns.size(); i++) {
                if (isMysql) sb.append("`").append(pkColumns.get(i)).append("` = ?");
                else sb.append(pkColumns.get(i)).append(" = ?");
                if (i < pkColumns.size() - 1) sb.append(" AND ");
            }
        }
        sb.append(";");
        return sb.toString();
    }

    public String buildDeleteSql(ConnectionConfig config, String dbName, String tableName, List<String> pkColumns) {
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("DELETE FROM ");
        sb.append(formatTable(config, dbName, tableName));

        if (pkColumns != null && !pkColumns.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < pkColumns.size(); i++) {
                if (isMysql) sb.append("`").append(pkColumns.get(i)).append("` = ?");
                else sb.append(pkColumns.get(i)).append(" = ?");
                if (i < pkColumns.size() - 1) sb.append(" AND ");
            }
        }
        sb.append(";");
        return sb.toString();
    }

    public static String formatTable(ConnectionConfig config, String dbName, String tableName) {
        if (config.getType() == DatabaseType.MYSQL) {
            if (dbName != null && !dbName.isEmpty()) {
                return "`" + dbName + "`.`" + tableName + "`";
            }
            return "`" + tableName + "`";
        } else {
            if (dbName != null && !dbName.isEmpty()) {
                return dbName + "." + tableName;
            }
            return tableName;
        }
    }

    private void appendMysqlColumnDef(StringBuilder sb, ColumnDefinition col) {
        sb.append("`").append(col.getName()).append("` ").append(col.getType());
        if (col.getSize() > 0 && needsSize(col.getType())) {
            sb.append("(").append(col.getSize()).append(")");
        }
        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (col.isAutoIncrement()) {
            sb.append(" AUTO_INCREMENT");
        }
        if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
            sb.append(" DEFAULT '").append(col.getDefaultValue().trim()).append("'");
        }
    }

    private void appendHsqlColumnDef(StringBuilder sb, ColumnDefinition col) {
        sb.append(col.getName()).append(" ");
        if (col.isAutoIncrement()) {
            sb.append("INT GENERATED BY DEFAULT AS IDENTITY");
        } else {
            sb.append(col.getType());
            if (col.getSize() > 0 && needsSize(col.getType())) {
                sb.append("(").append(col.getSize()).append(")");
            }
            if (!col.isNullable()) {
                sb.append(" NOT NULL");
            }
            if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
                sb.append(" DEFAULT '").append(col.getDefaultValue().trim()).append("'");
            }
        }
    }

    private boolean needsSize(String type) {
        String t = type.toUpperCase();
        return t.contains("CHAR") || t.contains("VARCHAR") || t.contains("VARBINARY") || t.contains("BINARY");
    }

    private void executeSql(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    public String getCreateTableStatement(Connection conn, ConnectionConfig config, String dbName, TableMetadata tableMetadata) {
        if (config.getType() == DatabaseType.MYSQL) {
            try (Statement stmt = conn.createStatement()) {
                String sql = (dbName != null && !dbName.trim().isEmpty())
                        ? "SHOW CREATE TABLE `" + dbName + "`.`" + tableMetadata.getName() + "`"
                        : "SHOW CREATE TABLE `" + tableMetadata.getName() + "`";
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        return rs.getString(2) + ";";
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return generateCreateTableSql(config, dbName, tableMetadata);
    }

    public String generateCreateTableSql(ConnectionConfig config, String dbName, TableMetadata tableMetadata) {
        StringBuilder sb = new StringBuilder();
        boolean isMysql = config.getType() == DatabaseType.MYSQL;

        sb.append("CREATE TABLE ");
        if (isMysql) {
            if (dbName != null && !dbName.trim().isEmpty()) {
                sb.append("`").append(dbName).append("`.");
            }
            sb.append("`").append(tableMetadata.getName()).append("` (\n");
        } else {
            if (dbName != null && !dbName.trim().isEmpty()) {
                sb.append(dbName).append(".");
            }
            sb.append(tableMetadata.getName()).append(" (\n");
        }

        List<ColumnMetadata> cols = tableMetadata.getColumns();
        List<String> pkCols = new ArrayList<>();

        for (int i = 0; i < cols.size(); i++) {
            ColumnMetadata col = cols.get(i);
            sb.append("  ");
            if (isMysql) {
                sb.append("`").append(col.getName()).append("` ").append(col.getFormattedType());
                if (!col.isNullable()) {
                    sb.append(" NOT NULL");
                }
                if (col.isAutoIncrement()) {
                    sb.append(" AUTO_INCREMENT");
                }
                if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
                    sb.append(" DEFAULT ").append(col.getDefaultValue().trim());
                }
            } else {
                sb.append(col.getName()).append(" ");
                if (col.isAutoIncrement()) {
                    sb.append(col.getTypeName()).append(" GENERATED BY DEFAULT AS IDENTITY");
                } else {
                    sb.append(col.getFormattedType());
                    if (!col.isNullable()) {
                        sb.append(" NOT NULL");
                    }
                    if (col.getDefaultValue() != null && !col.getDefaultValue().trim().isEmpty()) {
                        sb.append(" DEFAULT ").append(col.getDefaultValue().trim());
                    }
                }
            }

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
                if (isMysql) {
                    sb.append("`").append(pkCols.get(i)).append("`");
                } else {
                    sb.append(pkCols.get(i));
                }
                if (i < pkCols.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(")\n");
        }

        sb.append(")");
        if (isMysql) {
            sb.append(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        } else {
            sb.append(";");
        }

        return sb.toString();
    }
}
