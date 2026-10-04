package com.vibe.ideadb.service;

import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.DatabaseType;
import com.vibe.ideadb.model.QueryResult;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DataService {
    private static final DataService INSTANCE = new DataService();

    private DataService() {
    }

    public static DataService getInstance() {
        return INSTANCE;
    }

    public QueryResult fetchData(Connection conn, ConnectionConfig config, String dbName, String tableName,
                                 String whereClause, String orderBy, int limit, int offset) throws Exception {
        StringBuilder sb = new StringBuilder("SELECT * FROM ");
        sb.append(DdlService.formatTable(config, dbName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sb.append(" WHERE ").append(whereClause.trim());
        }

        if (orderBy != null && !orderBy.trim().isEmpty()) {
            sb.append(" ORDER BY ").append(orderBy.trim());
        }

        if (limit > 0) {
            sb.append(" LIMIT ").append(limit);
            if (offset > 0) {
                sb.append(" OFFSET ").append(offset);
            }
        }

        return executeQuery(conn, dbName, sb.toString());
    }

    public int countRows(Connection conn, ConnectionConfig config, String dbName, String tableName, String whereClause) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ");
        sb.append(DdlService.formatTable(config, dbName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sb.append(" WHERE ").append(whereClause.trim());
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sb.toString())) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public void updateCell(Connection conn, ConnectionConfig config, String dbName, String tableName,
                           String colName, Object newVal, Map<String, Object> pkVals) throws Exception {
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        changes.put(colName, newVal);
        updateRow(conn, config, dbName, tableName, changes, pkVals);
    }

    public void updateRow(Connection conn, ConnectionConfig config, String dbName, String tableName,
                          Map<String, Object> changes, Map<String, Object> pkVals) throws Exception {
        requireKeys(pkVals);
        if (changes.isEmpty()) return;
        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("UPDATE ");
        sb.append(DdlService.formatTable(config, dbName, tableName)).append(" SET ");
        List<String> columns = new ArrayList<>(changes.keySet());
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            sb.append(isMysql ? "`" + column.replace("`", "``") + "`" : column).append(" = ?");
            if (i < columns.size() - 1) sb.append(", ");
        }

        sb.append(" WHERE ");
        List<String> pkKeys = new ArrayList<>(pkVals.keySet());
        for (int i = 0; i < pkKeys.size(); i++) {
            String k = pkKeys.get(i);
            if (isMysql) sb.append("`").append(k).append("` = ?");
            else sb.append(k).append(" = ?");
            if (i < pkKeys.size() - 1) sb.append(" AND ");
        }

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < columns.size(); i++) ps.setObject(i + 1, changes.get(columns.get(i)));
            for (int i = 0; i < pkKeys.size(); i++) {
                ps.setObject(i + columns.size() + 1, pkVals.get(pkKeys.get(i)));
            }
            requireOneRow(ps.executeUpdate());
        }
    }

    public void insertRow(Connection conn, ConnectionConfig config, String dbName, String tableName,
                          Map<String, Object> values) throws Exception {
        if (values.isEmpty()) return;

        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(DdlService.formatTable(config, dbName, tableName)).append(" (");

        List<String> cols = new ArrayList<>(values.keySet());
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            if (isMysql) sb.append("`").append(col).append("`");
            else sb.append(col);
            if (i < cols.size() - 1) sb.append(", ");
        }
        sb.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            sb.append("?");
            if (i < cols.size() - 1) sb.append(", ");
        }
        sb.append(")");

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < cols.size(); i++) {
                ps.setObject(i + 1, values.get(cols.get(i)));
            }
            ps.executeUpdate();
        }
    }

    public void deleteRow(Connection conn, ConnectionConfig config, String dbName, String tableName,
                          Map<String, Object> pkVals) throws Exception {
        requireKeys(pkVals);

        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("DELETE FROM ");
        sb.append(DdlService.formatTable(config, dbName, tableName)).append(" WHERE ");

        List<String> pkKeys = new ArrayList<>(pkVals.keySet());
        for (int i = 0; i < pkKeys.size(); i++) {
            String k = pkKeys.get(i);
            if (isMysql) sb.append("`").append(k).append("` = ?");
            else sb.append(k).append(" = ?");
            if (i < pkKeys.size() - 1) sb.append(" AND ");
        }

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < pkKeys.size(); i++) {
                ps.setObject(i + 1, pkVals.get(pkKeys.get(i)));
            }
            requireOneRow(ps.executeUpdate());
        }
    }

    private static void requireKeys(Map<String, Object> keys) throws SQLException {
        if (keys.isEmpty() || keys.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new SQLException("Cannot modify a row without its complete original primary key");
        }
    }

    private static void requireOneRow(int affected) throws SQLException {
        if (affected != 1) throw new SQLException("Row conflict: expected one row, affected " + affected + ". Refresh and retry.");
    }

    public record RowUpdate(Map<String, Object> changes, Map<String, Object> originalKeys) {
        public RowUpdate {
            changes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(changes));
            originalKeys = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(originalKeys));
        }
    }

    public <T> T inTransaction(Connection conn, DatabaseSession.Operation<T> operation) throws Exception {
        synchronized (conn) {
            if (!conn.getAutoCommit()) throw new SQLException("A batch requires an exclusive connection without an existing transaction");
            conn.setAutoCommit(false);
            try {
                T result = operation.run(conn);
                conn.commit();
                return result;
            } catch (Exception | Error e) {
                try { conn.rollback(); } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                    try { conn.close(); } catch (SQLException close) { e.addSuppressed(close); }
                }
                throw e;
            } finally { if (!conn.isClosed()) conn.setAutoCommit(true); }
        }
    }

    public void commitChanges(Connection conn, ConnectionConfig config, String db, String table,
                              List<Map<String, Object>> inserts, List<RowUpdate> updates) throws Exception {
        requireTransactionalTable(conn, config, db, table);
        inTransaction(conn, connection -> {
            for (Map<String, Object> row : inserts) insertRow(connection, config, db, table, row);
            for (RowUpdate row : updates) updateRow(connection, config, db, table, row.changes(), row.originalKeys());
            return null;
        });
    }

    public void deleteRows(Connection conn, ConnectionConfig config, String db, String table,
                           List<Map<String, Object>> originalKeys) throws Exception {
        requireTransactionalTable(conn, config, db, table);
        inTransaction(conn, connection -> {
            for (Map<String, Object> keys : originalKeys) deleteRow(connection, config, db, table, keys);
            return null;
        });
    }

    private void requireTransactionalTable(Connection conn, ConnectionConfig config, String db, String table) throws SQLException {
        if (config.getType() != DatabaseType.MYSQL) return;
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT ENGINE FROM information_schema.tables WHERE table_schema=COALESCE(?,DATABASE()) AND table_name=?")) {
            statement.setString(1, db); statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"InnoDB".equalsIgnoreCase(result.getString(1))) {
                    throw new SQLException("Atomic grid changes require an InnoDB table");
                }
            }
        }
    }

    public QueryResult executeQuery(Connection conn, String dbName, String sql) {
        synchronized (conn) {
            return executeQueryLocked(conn, dbName, sql);
        }
    }

    private QueryResult executeQueryLocked(Connection conn, String dbName, String sql) {
        long start = System.currentTimeMillis();
        try {
            if (dbName != null && !dbName.trim().isEmpty()) {
                if (conn.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")) {
                    if (!dbName.equals(conn.getCatalog())) conn.setCatalog(dbName);
                } else {
                    if (!dbName.equals(conn.getSchema())) conn.setSchema(dbName);
                }
            }

            try (Statement stmt = conn.createStatement()) {
                boolean hasResultSet = stmt.execute(sql);
                long elapsed = System.currentTimeMillis() - start;

                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int colCount = md.getColumnCount();
                        List<String> cols = new ArrayList<>();
                        List<String> types = new ArrayList<>();

                        for (int i = 1; i <= colCount; i++) {
                            cols.add(md.getColumnLabel(i));
                            types.add(md.getColumnTypeName(i));
                        }

                        List<List<Object>> rows = new ArrayList<>();
                        while (rs.next()) {
                            List<Object> row = new ArrayList<>();
                            for (int i = 1; i <= colCount; i++) {
                                row.add(rs.getObject(i));
                            }
                            rows.add(row);
                        }

                        return QueryResult.forResultSet(cols, types, rows, elapsed);
                    }
                } else {
                    int affected = stmt.getUpdateCount();
                    return QueryResult.forUpdate(affected, elapsed);
                }
            }
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t.toString();
            return QueryResult.forError(msg, elapsed);
        }
    }
}
