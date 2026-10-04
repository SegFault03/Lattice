package com.vibe.ideadb;

import com.vibe.ideadb.model.*;
import com.vibe.ideadb.service.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Live regression tests use only unique temporary schemas and clean them on failure. */
public class FunctionalRegressionTest {
    private static final DatabaseConnectionManager manager = DatabaseConnectionManager.getInstance();
    private static final DataService data = DataService.getInstance();
    private static final DdlService ddl = DdlService.getInstance();
    private static final java.util.concurrent.atomic.AtomicInteger assertions = new java.util.concurrent.atomic.AtomicInteger();
    public static void check(boolean success, String message) {
        assertions.incrementAndGet();
        if (!success) throw new AssertionError(message);
    }
    public static void sql(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) { statement.execute(sql); }
    }
    public static Object scalar(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError("No scalar result");
            return result.getObject(1);
        }
    }
    private static void sessions(ConnectionConfig config, String schema) throws Exception {
        try (DatabaseSession first = manager.createSession(config); DatabaseSession second = manager.createSession(config)) {
            Connection a = first.execute(c -> c), b = second.execute(c -> c);
            check(a != b && a != manager.getConnection(config), "Consoles and browser must own separate connections");
            String namespace = config.getType() == DatabaseType.MYSQL ? "SELECT DATABASE()" : "VALUES (CURRENT_SCHEMA)";
            String other = config.getType() == DatabaseType.MYSQL ? "shop_db" : "PUBLIC";
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> left = executor.submit(() -> {
                    try {
                        for (int i = 0; i < 20; i++) first.execute(c -> {
                            QueryResult result = data.executeQuery(c, schema, namespace);
                            check(!result.hasError() && schema.equalsIgnoreCase(String.valueOf(result.getRows().get(0).get(0))), "First session namespace crossed: " + result.getError());
                            return null;
                        });
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                Future<?> right = executor.submit(() -> {
                    try {
                        for (int i = 0; i < 20; i++) second.execute(c -> {
                            QueryResult result = data.executeQuery(c, other, namespace);
                            check(!result.hasError() && other.equalsIgnoreCase(String.valueOf(result.getRows().get(0).get(0))), "Second session namespace crossed: " + result.getError());
                            return null;
                        });
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                left.get(20, TimeUnit.SECONDS); right.get(20, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
            first.close();
            check(a.isClosed() && !b.isClosed(), "Closing one console must not close another");
        }
        ConnectionConfig fresh = config.copy(); fresh.setId(UUID.randomUUID().toString());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CyclicBarrier gate = new CyclicBarrier(8);
            List<Future<Connection>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> { gate.await(); return manager.getConnection(fresh); }));
            Set<Connection> connections = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<Connection> result : results) connections.add(result.get(20, TimeUnit.SECONDS));
            check(connections.size() == 1, "Concurrent browser acquisition leaked connections");
        } finally { executor.shutdownNow(); manager.closeConnection(fresh.getId()); }
    }
    private static void runEngine(ConnectionConfig config) throws Exception {
        String schema = "LATTICE_FIX_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        Connection connection = manager.getConnection(config);
        ddl.createDatabase(connection, config, schema);
        try {
            sessions(config, schema);
            mutations(config, schema);
            columnAlterations(config, schema);
        } finally {
            if (config.getType() == DatabaseType.MYSQL) connection.setCatalog("shop_db");
            else connection.setSchema("PUBLIC");
            ddl.dropDatabase(connection, config, schema);
        }
        System.out.println("PASS " + config.getType() + " functional regressions (temporary schema removed)");
    }

    private static void columnAlterations(ConnectionConfig config, String schema) throws Exception {
        try (Connection c = manager.openConnection(config)) {
            String table = DdlService.formatTable(config,schema,"ALTER_RECORDS");
            if (config.getType()==DatabaseType.MYSQL) {
                sql(c,"CREATE TABLE " + table + " (ID INT AUTO_INCREMENT PRIMARY KEY, AMOUNT DECIMAL(12,2), KIND ENUM('a','b') DEFAULT 'a', MODIFIED TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, LABEL VARCHAR(100) COLLATE utf8mb4_unicode_ci COMMENT 'keep me')");
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("ID","BIGINT",0,false,false,false,""));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("AMOUNT","DECIMAL",12,true,false,false,""));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("KIND","ENUM",1,true,false,false,"a"));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("MODIFIED","TIMESTAMP",0,true,false,false,"CURRENT_TIMESTAMP"));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("LABEL","VARCHAR",200,true,false,false,""));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("LABEL","VARCHAR",200,false,false,false,"O'Reilly"));
                List<ColumnMetadata> cols = MetadataService.getInstance().getColumns(c,config,schema,"ALTER_RECORDS");
                check(cols.stream().filter(col->col.getName().equals("ID")).findFirst().orElseThrow().isAutoIncrement(),"Modify must retain identity");
                check(cols.stream().filter(col->col.getName().equals("AMOUNT")).findFirst().orElseThrow().getDecimalDigits()==2,"Modify must retain decimal scale");
                String create = ddl.getCreateTableStatement(c,config,schema,new TableMetadata(schema,null,"ALTER_RECORDS","TABLE"));
                check(create.toLowerCase(Locale.ROOT).contains("enum('a','b')"),"Modify must retain ENUM values");
                check(create.toLowerCase(Locale.ROOT).contains("on update current_timestamp"),"Modify must retain ON UPDATE");
                check(create.contains("keep me") && create.contains("utf8mb4_unicode_ci"),"Modify must retain comment/collation");
                check(cols.stream().filter(col->col.getName().equals("LABEL")).findFirst().orElseThrow().isNullable()==false,"Modify must apply nullability with collation");
            }
            ddl.createTable(c,config,schema,"DEFAULT_RECORDS",List.of(new ColumnDefinition("LABEL","VARCHAR",100,false,false,false,"O'Reilly"),new ColumnDefinition("CREATED","TIMESTAMP",0,true,false,false,"CURRENT_TIMESTAMP")));
            sql(c,"INSERT INTO " + DdlService.formatTable(config,schema,"DEFAULT_RECORDS") + " (CREATED) VALUES (CURRENT_TIMESTAMP)");
            check("O'Reilly".equals(scalar(c,"SELECT LABEL FROM " + DdlService.formatTable(config,schema,"DEFAULT_RECORDS"))),"String default must round-trip");
        }
    }

    private static void expectFailure(DatabaseSession.Operation<?> operation, Connection conn) throws Exception {
        boolean failed = false;
        try { operation.run(conn); } catch (SQLException expected) { failed = true; }
        check(failed, "Invalid/stale mutation must fail");
    }

    private static void mutations(ConnectionConfig config, String schema) throws Exception {
        String table = DdlService.formatTable(config, schema, "GRID_RECORDS");
        try (Connection c = manager.openConnection(config)) {
            sql(c, "CREATE TABLE " + table + " (ID INT, TENANT INT, VAL VARCHAR(100), PRIMARY KEY(ID,TENANT))");
            data.insertRow(c, config, schema, "GRID_RECORDS", Map.of("ID",1,"TENANT",7,"VAL","original"));
            data.insertRow(c, config, schema, "GRID_RECORDS", Map.of("ID",2,"TENANT",7,"VAL","unrelated"));
            Map<String, Object> original = RowIdentity.originalKeys(List.of("ID","TENANT","VAL"), List.of(1,7,"original"), List.of("ID","TENANT"));
            LinkedHashMap<String,Object> changes = new LinkedHashMap<>(); changes.put("ID",3); changes.put("VAL","edited");
            data.commitChanges(c, config, schema, "GRID_RECORDS", List.of(), List.of(new DataService.RowUpdate(changes, original)));
            check("edited".equals(scalar(c,"SELECT VAL FROM " + table + " WHERE ID=3 AND TENANT=7")), "Key and value must update together");
            Map<String,Object> originalAfterLoad = RowIdentity.originalKeys(List.of("ID","TENANT","VAL"), List.of(3,7,"edited"), List.of("ID","TENANT"));
            data.deleteRows(c, config, schema, "GRID_RECORDS", List.of(originalAfterLoad));
            check("unrelated".equals(scalar(c,"SELECT VAL FROM " + table + " WHERE ID=2 AND TENANT=7")), "Delete must preserve unrelated row");
            expectFailure(conn -> { data.commitChanges(conn,config,schema,"GRID_RECORDS",List.of(Map.of("ID",9,"TENANT",7),Map.of("ID",9,"TENANT",7)),List.of()); return null; }, c);
            check(((Number)scalar(c,"SELECT COUNT(*) FROM " + table + " WHERE ID=9")).intValue()==0,"Failed inserts must roll back");
            data.commitChanges(c,config,schema,"GRID_RECORDS",List.of(Map.of("ID",9,"TENANT",7),Map.of("ID",10,"TENANT",7)),List.of());
            check(((Number)scalar(c,"SELECT COUNT(*) FROM " + table + " WHERE ID IN (9,10)")).intValue()==2,"Retry must persist each insert once");
            expectFailure(conn -> { data.commitChanges(conn,config,schema,"GRID_RECORDS",List.of(Map.of("ID",11,"TENANT",7)),List.of(new DataService.RowUpdate(Map.of("VAL","missing"),Map.of("ID",99,"TENANT",7)))); return null; },c);
            check(((Number)scalar(c,"SELECT COUNT(*) FROM " + table + " WHERE ID=11")).intValue()==0,"Stale update must roll back inserts");
            expectFailure(conn -> { data.deleteRows(conn,config,schema,"GRID_RECORDS",List.of(Map.of("ID",9,"TENANT",7),Map.of("ID",99,"TENANT",7))); return null; },c);
            check(((Number)scalar(c,"SELECT COUNT(*) FROM " + table + " WHERE ID=9")).intValue()==1,"Failed delete batch must roll back");
            expectFailure(conn -> { data.deleteRow(conn,config,schema,"GRID_RECORDS",Map.of()); return null; },c);
            check(c.getAutoCommit(),"Transaction must restore auto-commit after success/failure");
            c.setAutoCommit(false);
            expectFailure(conn -> data.inTransaction(conn, unused -> null),c);
            check(!c.getAutoCommit(),"Existing transactions must not be changed or committed");
            c.rollback(); c.setAutoCommit(true);
        }
    }
    public static void main(String[] args) throws Exception {
        try {
            ConnectionConfig mysql = new ConnectionConfig(DatabaseType.MYSQL, "regression MySQL"); mysql.setDatabaseName("shop_db");
            runEngine(mysql);
            runEngine(new ConnectionConfig(DatabaseType.HSQLDB, "regression HSQLDB"));
            System.out.println("PASS functional regression assertions: " + assertions);
        } finally { manager.closeAll(); }
    }
}
