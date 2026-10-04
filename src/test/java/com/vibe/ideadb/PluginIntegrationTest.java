package com.vibe.ideadb;

import com.vibe.ideadb.model.*;
import com.vibe.ideadb.service.*;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.*;

public class PluginIntegrationTest {
    public static void main(String[] args) {
        try {
            System.out.println("Starting Lattice Plugin Integration Tests...");

            testDriverLoading();
            testSelectedTypeAndLiveConnections();
            testHsqlConnectionAndMetadata();
            testCrudAndInlineEdits();
            testExport();
            testAlterAndDrop();
            testDdlGenerators();

            System.out.println("\nALL INTEGRATION TESTS PASSED SUCCESSFULLY! (100% OK)");
        } catch (Throwable t) {
            System.err.println("TEST FAILED:");
            t.printStackTrace();
            throw new AssertionError("Integration suite failed",t);
        }
    }

    private static void testDriverLoading() throws Exception {
        System.out.print("[TEST] Driver Loading (HSQLDB & MySQL)... ");
        var hsqlDriver = DriverRegistry.getInstance().getDriver(DatabaseType.HSQLDB);
        if (hsqlDriver == null) throw new AssertionError("HSQLDB driver null");

        var mysqlDriver = DriverRegistry.getInstance().getDriver(DatabaseType.MYSQL);
        if (mysqlDriver == null) throw new AssertionError("MySQL driver null");

        System.out.println("PASSED! HSQLDB: " + hsqlDriver.getClass().getName() + ", MySQL: " + mysqlDriver.getClass().getName());
    }

    private static void testSelectedTypeAndLiveConnections() throws Exception {
        System.out.print("[TEST] Wrong protocol does not change database type... ");
        ConnectionConfig wrong = new ConnectionConfig(DatabaseType.MYSQL, "Wrong protocol");
        wrong.setPort(9001); wrong.setDatabaseName("testdb"); wrong.setUser("SA");
        ConnectionTestResult rejected = DatabaseConnectionManager.getInstance().testConnection(wrong);
        if (rejected.isSuccess() || wrong.getType() != DatabaseType.MYSQL) {
            throw new AssertionError("A protocol failure must not switch the selected database type");
        }
        ConnectionConfig hsql = new ConnectionConfig(DatabaseType.HSQLDB, "Explicit HSQLDB");
        ConnectionTestResult detected = DatabaseConnectionManager.getInstance().testConnection(hsql);
        if (!detected.isSuccess() || !detected.getDatabaseProductName().contains("HSQL")) {
            throw new AssertionError("Explicit HSQLDB connection/version detection failed");
        }
        System.out.println("PASSED!");

        System.out.print("[TEST] Live MySQL server on 3306... ");
        ConnectionConfig mysqlLiveCfg = new ConnectionConfig(DatabaseType.MYSQL, "Live MySQL");
        mysqlLiveCfg.setHost("localhost");
        mysqlLiveCfg.setPort(3306);
        mysqlLiveCfg.setDatabaseName("shop_db");
        mysqlLiveCfg.setUser("root");
        mysqlLiveCfg.setPassword("");

        ConnectionTestResult resMysql = DatabaseConnectionManager.getInstance().testConnection(mysqlLiveCfg);
        if (!resMysql.isSuccess()) {
            throw new AssertionError("Live MySQL test connection failed: " + resMysql.getErrorMessage());
        }
        System.out.println("PASSED! (Connected to: " + resMysql.getDatabaseProductName() + ")");

        System.out.print("[TEST] Custom JDBC URL mode with User & Password... ");
        ConnectionConfig customCfg = new ConnectionConfig();
        customCfg.setCustomUrl("jdbc:mysql://localhost:3306/shop_db");
        customCfg.setUser("root");
        customCfg.setPassword("");
        ConnectionTestResult resCustom = DatabaseConnectionManager.getInstance().testConnection(customCfg);
        if (!resCustom.isSuccess()) {
            throw new AssertionError("Custom JDBC URL test failed: " + resCustom.getErrorMessage());
        }
        System.out.println("PASSED! (Custom URL connected to: " + resCustom.getDatabaseProductName() + ")");
    }

    private static void testHsqlConnectionAndMetadata() throws Exception {
        System.out.print("[TEST] Connection & Metadata Inspection... ");
        ConnectionConfig cfg = new ConnectionConfig(DatabaseType.HSQLDB, "Test In-Memory");
        cfg.setHsqlMode(HsqlMode.MEM);
        cfg.setDatabaseName("test_integration_db");

        ConnectionTestResult testResult = DatabaseConnectionManager.getInstance().testConnection(cfg);
        if (!testResult.isSuccess()) {
            throw new AssertionError("Connection test failed: " + testResult.getErrorMessage());
        }

        Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);
        List<String> dbs = MetadataService.getInstance().getDatabases(conn, cfg);
        if (dbs.isEmpty()) throw new AssertionError("Databases list empty");

        // Create table
        List<ColumnDefinition> cols = new ArrayList<>();
        cols.add(new ColumnDefinition("id", "INT", 0, false, true, true, ""));
        cols.add(new ColumnDefinition("username", "VARCHAR", 100, false, false, false, ""));
        cols.add(new ColumnDefinition("email", "VARCHAR", 150, true, false, false, ""));

        DdlService.getInstance().createTable(conn, cfg, "PUBLIC", "users", cols);

        List<TableMetadata> tables = MetadataService.getInstance().getTables(conn, cfg, "PUBLIC");
        boolean foundUsers = tables.stream().anyMatch(t -> t.getName().equalsIgnoreCase("users"));
        if (!foundUsers) throw new AssertionError("Created table 'users' not found in metadata");

        List<ColumnMetadata> colMetas = MetadataService.getInstance().getColumns(conn, cfg, "PUBLIC", "users");
        if (colMetas.size() < 3) throw new AssertionError("Expected at least 3 columns, got: " + colMetas.size());

        boolean pkFound = colMetas.stream().anyMatch(c -> c.getName().equalsIgnoreCase("id") && c.isPrimaryKey());
        if (!pkFound) throw new AssertionError("Primary key not detected on column 'id'");

        System.out.println("PASSED! (Found tables: " + tables.size() + ", Columns: " + colMetas.size() + ")");
    }

    private static void testCrudAndInlineEdits() throws Exception {
        System.out.print("[TEST] CRUD Operations & Pagination... ");
        ConnectionConfig cfg = new ConnectionConfig(DatabaseType.HSQLDB, "Test CRUD");
        cfg.setHsqlMode(HsqlMode.MEM);
        cfg.setDatabaseName("test_integration_db");
        Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);

        // Insert
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", 1);
        row1.put("username", "alice");
        row1.put("email", "alice@example.com");
        DataService.getInstance().insertRow(conn, cfg, "PUBLIC", "users", row1);

        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", 2);
        row2.put("username", "bob");
        row2.put("email", "bob@example.com");
        DataService.getInstance().insertRow(conn, cfg, "PUBLIC", "users", row2);

        // Fetch
        QueryResult res = DataService.getInstance().fetchData(conn, cfg, "PUBLIC", "users", null, "\"id\" ASC", 10, 0);
        if (res.getRows().size() != 2) throw new AssertionError("Expected 2 rows, got: " + res.getRows().size());

        // Update cell
        Map<String, Object> pkVals = Map.of("id", 1);
        DataService.getInstance().updateCell(conn, cfg, "PUBLIC", "users", "email", "alice_new@example.com", pkVals);

        QueryResult updatedRes = DataService.getInstance().fetchData(conn, cfg, "PUBLIC", "users", "\"id\" = 1", null, 10, 0);
        Object updatedEmail = updatedRes.getRows().get(0).get(2);
        if (!"alice_new@example.com".equals(updatedEmail)) {
            throw new AssertionError("Cell update failed! Got: " + updatedEmail);
        }

        // Delete row
        DataService.getInstance().deleteRow(conn, cfg, "PUBLIC", "users", Map.of("id", 2));
        QueryResult afterDelete = DataService.getInstance().fetchData(conn, cfg, "PUBLIC", "users", null, null, 10, 0);
        if (afterDelete.getRows().size() != 1) {
            throw new AssertionError("Delete failed! Remaining rows: " + afterDelete.getRows().size());
        }

        System.out.println("PASSED!");
    }

    private static void testExport() throws Exception {
        System.out.print("[TEST] Export CSV, JSON & SQL... ");
        List<String> cols = List.of("id", "name", "active");
        List<String> types = List.of("INT", "VARCHAR", "BOOLEAN");
        List<List<Object>> rows = List.of(
                List.of(1, "Product A", true),
                List.of(2, "Product \"Special\", B", false)
        );
        QueryResult result = QueryResult.forResultSet(cols, types, rows, 5);

        File csv = File.createTempFile("test_export", ".csv");
        File json = File.createTempFile("test_export", ".json");
        File sql = File.createTempFile("test_export", ".sql");
        csv.deleteOnExit();
        json.deleteOnExit();
        sql.deleteOnExit();

        ExportService.getInstance().exportToCsv(result, csv);
        ExportService.getInstance().exportToJson(result, json);
        ExportService.getInstance().exportToSqlInsert("products", result, sql);

        String csvText = Files.readString(csv.toPath());
        if (!csvText.contains("Product A") || !csvText.contains("\"Product \"\"Special\"\", B\"")) {
            throw new AssertionError("CSV export formatting incorrect:\n" + csvText);
        }

        String jsonText = Files.readString(json.toPath());
        if (!jsonText.contains("\"name\": \"Product A\"")) {
            throw new AssertionError("JSON export invalid:\n" + jsonText);
        }

        String sqlText = Files.readString(sql.toPath());
        if (!sqlText.contains("INSERT INTO \"products\"")) {
            throw new AssertionError("SQL export invalid:\n" + sqlText);
        }

        System.out.println("PASSED!");
    }

    private static void testAlterAndDrop() throws Exception {
        System.out.print("[TEST] Table Alterations & Drop... ");
        ConnectionConfig cfg = new ConnectionConfig(DatabaseType.HSQLDB, "Test Alter");
        cfg.setHsqlMode(HsqlMode.MEM);
        cfg.setDatabaseName("test_integration_db");
        Connection conn = DatabaseConnectionManager.getInstance().getConnection(cfg);

        // Add Column
        ColumnDefinition colBio = new ColumnDefinition("bio", "VARCHAR", 255, true, false, false, "");
        DdlService.getInstance().alterTableAddColumn(conn, cfg, "PUBLIC", "users", colBio);

        List<ColumnMetadata> colsAfter = MetadataService.getInstance().getColumns(conn, cfg, "PUBLIC", "users");
        if (colsAfter.stream().noneMatch(c -> c.getName().equalsIgnoreCase("bio"))) {
            throw new AssertionError("Added column 'bio' not found in metadata");
        }

        // Truncate Table
        DdlService.getInstance().truncateTable(conn, cfg, "PUBLIC", "users");
        long count = DataService.getInstance().countRows(conn, cfg, "PUBLIC", "users", null);
        if (count != 0) throw new AssertionError("Table truncate failed, row count: " + count);

        // Drop Table
        DdlService.getInstance().dropTable(conn, cfg, "PUBLIC", "users");
        List<TableMetadata> tablesAfterDrop = MetadataService.getInstance().getTables(conn, cfg, "PUBLIC");
        if (tablesAfterDrop.stream().anyMatch(t -> t.getName().equalsIgnoreCase("users"))) {
            throw new AssertionError("Table 'users' still exists after drop");
        }

        System.out.println("PASSED!");
    }

    private static void testDdlGenerators() {
        System.out.print("[TEST] DDL SQL Syntaxes (MySQL & HSQLDB)... ");
        ConnectionConfig mysqlCfg = new ConnectionConfig(DatabaseType.MYSQL, "MySQL Test");
        List<ColumnDefinition> cols = List.of(
                new ColumnDefinition("id", "INT", 11, false, true, true, ""),
                new ColumnDefinition("title", "VARCHAR", 255, false, false, false, "Draft")
        );

        String mysqlDdl = DdlService.getInstance().buildCreateTableSql(mysqlCfg, "shop", "items", cols);
        if (!mysqlDdl.contains("`shop`.`items`") || !mysqlDdl.contains("AUTO_INCREMENT") || !mysqlDdl.contains("ENGINE=InnoDB")) {
            throw new AssertionError("MySQL DDL generation mismatch: " + mysqlDdl);
        }

        ConnectionConfig hsqlCfg = new ConnectionConfig(DatabaseType.HSQLDB, "HSQLDB Test");
        String hsqlDdl = DdlService.getInstance().buildCreateTableSql(hsqlCfg, "PUBLIC", "items", cols);
        if (!hsqlDdl.contains("\"PUBLIC\".\"items\"") || !hsqlDdl.contains("GENERATED BY DEFAULT AS IDENTITY")) {
            throw new AssertionError("HSQLDB DDL generation mismatch: " + hsqlDdl);
        }

        System.out.println("PASSED!");
    }
}
