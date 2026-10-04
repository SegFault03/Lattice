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

    public QueryResult fetchData(Connection conn,ConnectionConfig config,String db,String table,String where,String order,int limit,int offset) throws Exception {
        List<String> keys=order==null || order.isBlank() ? MetadataService.getInstance().getColumns(conn,config,db,table).stream().filter(com.vibe.ideadb.model.ColumnMetadata::isPrimaryKey).map(com.vibe.ideadb.model.ColumnMetadata::getName).toList() : List.of();
        return fetchData(conn,config,db,table,where,order,limit,offset,keys);
    }
    public QueryResult fetchData(Connection conn,ConnectionConfig config,String db,String table,String where,String order,int limit,int offset,List<String> keys) throws Exception {
        if(limit<0 || offset<0) throw new IllegalArgumentException("Page limit/offset must not be negative");
        StringBuilder sql=new StringBuilder("SELECT * FROM ").append(DdlService.formatTable(config,db,table));
        if(where!=null && !where.isBlank()) sql.append(" WHERE ").append(where.trim());
        if(order!=null && !order.isBlank()) sql.append(" ORDER BY ").append(order.trim());
        else if(!keys.isEmpty()) sql.append(" ORDER BY ").append(keys.stream().map(key -> DdlService.quoteIdentifier(config,key)).collect(java.util.stream.Collectors.joining(", ")));
        if(limit>0) { sql.append(" LIMIT ").append(limit); if(offset>0) sql.append(" OFFSET ").append(offset); }
        return executeQuery(conn,db,sql.toString());
    }
    public long countRows(Connection conn,ConnectionConfig config,String db,String table,String where) throws Exception {
        String sql="SELECT COUNT(*) FROM " + DdlService.formatTable(config,db,table) + (where==null || where.isBlank() ? "" : " WHERE " + where.trim());
        synchronized(conn) {
            try(Statement statement=conn.createStatement()) {
                statement.setQueryTimeout(60);
                try(ResultSet result=statement.executeQuery(sql)) {
                    if(!result.next()) throw new SQLException("Row count unavailable");
                    return result.getLong(1);
                }
            }
        }
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
            sb.append(DdlService.quoteIdentifier(config,column)).append(" = ?");
            if (i < columns.size() - 1) sb.append(", ");
        }

        sb.append(" WHERE ");
        List<String> pkKeys = new ArrayList<>(pkVals.keySet());
        for (int i = 0; i < pkKeys.size(); i++) {
            String k = pkKeys.get(i);
            sb.append(DdlService.quoteIdentifier(config,k)).append(" = ?");
            if (i < pkKeys.size() - 1) sb.append(" AND ");
        }

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < columns.size(); i++) bind(ps,i + 1, changes.get(columns.get(i)));
            for (int i = 0; i < pkKeys.size(); i++) {
                bind(ps,i + columns.size() + 1, pkVals.get(pkKeys.get(i)));
            }
            requireOneRow(ps.executeUpdate());
        }
    }

    public void insertRow(Connection conn, ConnectionConfig config, String dbName, String tableName,
                          Map<String, Object> values) throws Exception {
        if (values.isEmpty()) {
            String target=DdlService.formatTable(config,dbName,tableName);
            try(PreparedStatement statement=conn.prepareStatement("INSERT INTO " + target + (config.getType()==DatabaseType.MYSQL ? " () VALUES ()" : " DEFAULT VALUES"))) { requireOneRow(statement.executeUpdate()); }
            return;
        }

        boolean isMysql = config.getType() == DatabaseType.MYSQL;
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(DdlService.formatTable(config, dbName, tableName)).append(" (");

        List<String> cols = new ArrayList<>(values.keySet());
        for (int i = 0; i < cols.size(); i++) {
            String col = cols.get(i);
            sb.append(DdlService.quoteIdentifier(config,col));
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
                bind(ps,i + 1, values.get(cols.get(i)));
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
            sb.append(DdlService.quoteIdentifier(config,k)).append(" = ?");
            if (i < pkKeys.size() - 1) sb.append(" AND ");
        }

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            for (int i = 0; i < pkKeys.size(); i++) {
                bind(ps,i + 1, pkVals.get(pkKeys.get(i)));
            }
            requireOneRow(ps.executeUpdate());
        }
    }

    private static void bind(PreparedStatement statement,int index,Object value) throws SQLException {
        // Connector/J maps BigInteger through signed BIGINT unless it is bound as DECIMAL.
        statement.setObject(index,value instanceof java.math.BigInteger integer ? new java.math.BigDecimal(integer) : value);
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

    /** Takes ownership; cleanup errors must never turn an acknowledged mutation into a retry. */
    public <T> T withMutationConnection(Connection conn, DatabaseSession.Operation<T> operation) throws Exception {
        try { return operation.run(conn); }
        finally { discardConnection(conn); }
    }

    private static void discardConnection(Connection conn) {
        try { conn.close(); }
        catch (SQLException cleanup) { logCleanup(cleanup); }
    }

    private static void logCleanup(SQLException cleanup) {
        java.util.logging.Logger.getLogger(DataService.class.getName()).warning("JDBC cleanup failed; connection discarded: " + cleanup.getMessage());
    }

    public <T> T inTransaction(Connection conn, DatabaseSession.Operation<T> operation) throws Exception {
        synchronized (conn) {
            if (!conn.getAutoCommit()) throw new SQLException("A batch requires an exclusive connection without an existing transaction");
            conn.setAutoCommit(false);
            Throwable failure = null;
            boolean restoreAutoCommit = true;
            try {
                T result = operation.run(conn);
                conn.commit();
                return result;
            } catch (Exception | Error e) {
                failure = e;
                try { conn.rollback(); } catch (SQLException rollback) {
                    restoreAutoCommit = false; // enabling auto-commit could commit the unrolled-back writes
                    e.addSuppressed(rollback);
                    try { conn.close(); } catch (SQLException close) { e.addSuppressed(close); }
                }
                throw e;
            } finally {
                try { if (restoreAutoCommit && !conn.isClosed()) conn.setAutoCommit(true); }
                catch (SQLException cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else logCleanup(cleanup); // commit returned successfully; preserve that outcome
                    discardConnection(conn);
                }
            }
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

    public record QueryOptions(int maxRows, int timeoutSeconds, int fetchSize) {
        public QueryOptions {
            if (maxRows < 1 || maxRows > 1_000_000 || timeoutSeconds < 1 || fetchSize < 1) throw new IllegalArgumentException("Invalid query limits");
        }
        public static QueryOptions defaults() { return new QueryOptions(10000,60,100); }
    }
    public QueryResult executeQuery(Connection conn, String dbName, String sql) {
        return executeQuery(conn, dbName, sql, QueryOptions.defaults(), new QueryExecution());
    }
    public QueryResult executeQuery(Connection conn, String dbName, String sql, QueryOptions options, QueryExecution execution) {
        synchronized (conn) { return executeQueryLocked(conn, dbName, sql, options, execution); }
    }
    private QueryResult executeQueryLocked(Connection conn, String dbName, String sql, QueryOptions options, QueryExecution execution) {
        long start = System.nanoTime();
        try {
            execution.checkCancelled();
            boolean mysql = conn.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
            if (dbName != null && !dbName.trim().isEmpty()) {
                if (mysql) { if (!dbName.equals(conn.getCatalog())) conn.setCatalog(dbName); }
                else { if (!dbName.equals(conn.getSchema())) conn.setSchema(dbName); }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(options.timeoutSeconds());
                stmt.setMaxRows(options.maxRows() + 1);
                // Connector/J's streaming sentinel prevents buffering the whole result client-side.
                stmt.setFetchSize(mysql ? Integer.MIN_VALUE : options.fetchSize());
                execution.attach(stmt);
                execution.checkCancelled();
                boolean hasResultSet = stmt.execute(sql);
                execution.checkCancelled();
                if (!hasResultSet) return QueryResult.forUpdate(stmt.getUpdateCount(), elapsed(start));
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int colCount = md.getColumnCount();
                    List<String> cols = new ArrayList<>(), types = new ArrayList<>();
                    for (int i=1;i<=colCount;i++) { cols.add(md.getColumnLabel(i)); types.add(md.getColumnTypeName(i)); }
                    List<List<Object>> rows = new ArrayList<>();
                    long bytes = 0;
                    boolean truncated = false;
                    while (rs.next()) {
                        execution.checkCancelled();
                        if (rows.size() >= options.maxRows()) { truncated=true; break; }
                        List<Object> row = new ArrayList<>();
                        for (int i=1;i<=colCount;i++) {
                            Object value = detachValue(rs.getObject(i)); row.add(value);
                            bytes += value instanceof byte[] binary ? binary.length : value instanceof String text ? text.length()*2L : 32;
                        }
                        if (bytes > 16L * 1024 * 1024) { truncated=true; break; }
                        rows.add(row);
                    }
                    return QueryResult.forResultSet(cols,types,rows,elapsed(start),truncated);
                }
            }
        } catch (Exception error) {
            String message = error.getMessage();
            return QueryResult.forError(message == null || message.isEmpty() ? error.toString() : message, elapsed(start));
        } finally { execution.detach(); }
    }
    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }

    /** LOBs must remain usable after the ResultSet and Statement have been closed. */
    static Object detachValue(Object value) throws SQLException {
        if (value instanceof Blob blob) {
            try { if (blob.length()>2L*1024*1024) throw new SQLException("Binary value exceeds the 2 MiB display limit"); return blob.getBytes(1, Math.toIntExact(blob.length())); } finally { blob.free(); }
        }
        if (value instanceof Clob clob) {
            try { if (clob.length()>1024*1024) throw new SQLException("Text value exceeds the 1 Mi-character display limit"); return clob.getSubString(1, Math.toIntExact(clob.length())); } finally { clob.free(); }
        }
        return value;
    }

}
