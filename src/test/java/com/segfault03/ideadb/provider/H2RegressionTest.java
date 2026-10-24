package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real database safety regressions; shared by embedded drivers, compatibility modes and bundled TCP/TLS. */
public class H2RegressionTest {
    private static ConnectionConfig config() {
        var config = new ConnectionConfig(DatabaseType.H2, "regression");
        config.setDatabaseName("regression_" + UUID.randomUUID()); return config;
    }
    @Test void typedValuesAndAllExports() throws Exception { valuesFlow(config()); }
    @Test void identityGeneratedConstraintsAndNativeDdlReplay() throws Exception { catalogFlow(config()); }
    @Test void failedBatchAndAlterPreserveDataAndOuterTransaction() throws Exception { transactionFlow(config()); }
    @Test void cancellationTimeoutAndSessionRecovery() throws Exception { cancellationFlow(config()); }
    @Test void columnEditsPreserveUpdateExpressionsAndIdentityState() throws Exception { alterationFlow(config()); }
    public static void regressionFlow(ConnectionConfig config) throws Exception {
        valuesFlow(config); catalogFlow(config); transactionFlow(config); cancellationFlow(config); alterationFlow(config);
    }
    public static void alterationFlow(ConnectionConfig config) throws Exception {
        scoped(config, (conn, schema) -> {
            String target = DdlService.formatTable(config, schema, "alter_attributes");
            sql(conn, "CREATE TABLE " + target + " (\"id\" BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 100 INCREMENT BY 10) PRIMARY KEY, \"updated\" TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, \"value\" INT)");
            sql(conn, "INSERT INTO " + target + " (\"updated\") VALUES (TIMESTAMP '2000-01-01 00:00:00'), (TIMESTAMP '2000-01-01 00:00:00')");
            var service = DdlService.getInstance();
            var columns = MetadataService.getInstance().getColumns(conn, config, schema, "alter_attributes");
            var updated = columns.get(1);
            var nullable = TableAlteration.column(TableAlteration.Kind.MODIFY, updated, "updated", updated.getTypeName(), "", false, updated.getDefaultValue());
            var preview = service.previewAlter(conn, config, schema, "alter_attributes", nullable);
            assertEquals(1, preview.size(), "A nullability edit must not replace the complete definition");
            service.applyAlter(conn, config, schema, "alter_attributes", nullable);
            sql(conn, "UPDATE " + target + " SET \"value\"=1 WHERE \"id\"=100");
            assertEquals(Boolean.TRUE, scalar(conn, "SELECT \"updated\">TIMESTAMP '2000-01-01 00:00:00' FROM " + target + " WHERE \"id\"=100"), "ON UPDATE must still execute");
            var identity = columns.getFirst();
            var integer = TableAlteration.column(TableAlteration.Kind.MODIFY, identity, "id", "INTEGER", "", false, "");
            service.applyAlter(conn, config, schema, "alter_attributes", integer);
            sql(conn, "INSERT INTO " + target + " (\"value\") VALUES (3)");
            assertEquals(120, ((Number) scalar(conn, "SELECT \"id\" FROM " + target + " WHERE \"value\"=3")).intValue(), "Identity increment and next value must survive a type change");
            assertThrows(SQLException.class, () -> sql(conn, "INSERT INTO " + target + " (\"id\") VALUES (500)"), "GENERATED ALWAYS must remain enforced");
            assertEquals(Types.INTEGER, MetadataService.getInstance().getColumns(conn, config, schema, "alter_attributes").getFirst().getDataType());
        });
    }
    public static void valuesFlow(ConnectionConfig config) throws Exception {
        scoped(config, (conn, schema) -> {
            String target = DdlService.formatTable(config, schema, "typed_%");
            sql(conn, "CREATE TABLE " + target + " (ID INT PRIMARY KEY, TXT VARCHAR(200), BYTES VARBINARY(20), TS TIMESTAMP(6), TZ TIMESTAMP(6) WITH TIME ZONE, AMOUNT DECIMAL(30,6), FLAG BOOLEAN, EMPTY_TXT VARCHAR(10))");
            var values = new LinkedHashMap<String,Object>();
            values.put("ID", 1); values.put("TXT", "O'Brien\\path\nemoji 😀"); values.put("BYTES", new byte[]{0,1,(byte)255});
            values.put("TS", java.time.LocalDateTime.parse("2026-10-15T12:34:56.123456"));
            values.put("TZ", java.time.OffsetDateTime.parse("2026-10-15T12:34:56.123456+05:30"));
            values.put("AMOUNT", new java.math.BigDecimal("12345678901234567890.123456")); values.put("FLAG", true); values.put("EMPTY_TXT", "");
            var data = DataService.getInstance(); data.commitChanges(conn, config, schema, "typed_%", List.of(values, Map.of("ID",2)), List.of());
            var original = data.fetchData(conn, config, schema, "typed_%", null, null, 10, 0);
            assertFalse(original.hasError(), original.getError()); assertNull(original.getRows().get(1).get(1));
            for (String format : List.of("csv", "json", "sql")) {
                Path file = Files.createTempFile("h2-values-", "." + format);
                try {
                    assertEquals(2, ExportService.getInstance().exportPersisted(conn, config, schema, "typed_%", null, format, file.toFile()));
                    String exported = Files.readString(file); assertTrue(exported.contains("O'Brien") || exported.contains("O''Brien"));
                    assertTrue(exported.contains("😀") || exported.toLowerCase(java.util.Locale.ROOT).contains("\\ud83d\\ude00"), exported);
                    if (format.equals("sql")) {
                        DdlService.getInstance().truncateTable(conn, config, schema, "typed_%"); sql(conn, exported);
                        var replay = data.fetchData(conn, config, schema, "typed_%", null, null, 10, 0);
                        for (int r=0;r<2;r++) for (int c=0;c<original.getColumnNames().size();c++) {
                            var expected=original.getRows().get(r).get(c); var actual=replay.getRows().get(r).get(c);
                            if (expected instanceof byte[] bytes) assertArrayEquals(bytes,(byte[])actual); else assertEquals(expected,actual);
                        }
                    }
                } finally { Files.deleteIfExists(file); }
            }
        });
    }
    public static void catalogFlow(ConnectionConfig config) throws Exception {
        scoped(config, (conn, schema) -> {
            var ddl = DdlService.getInstance(); var data = DataService.getInstance();
            String target = DdlService.formatTable(config, schema, "records_%");
            sql(conn, "CREATE TABLE " + target + " (ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, VALUE_TXT VARCHAR(80) DEFAULT 'O''Brien', DOUBLED BIGINT GENERATED ALWAYS AS (ID*2), CONSTRAINT CHECK_TEXT CHECK(VALUE_TXT<>'blocked'))");
            sql(conn, "CREATE INDEX \"index_%\" ON " + target + " (VALUE_TXT)");
            data.insertRow(conn, config, schema, "records_%", Map.of());
            var metadata = MetadataService.getInstance().getTables(conn, config, schema).getFirst();
            String nativeDdl = ddl.getCreateTableStatement(conn, config, schema, metadata);
            assertTrue(nativeDdl.contains("CHECK_TEXT")); assertTrue(nativeDdl.contains("index_%"));
            var file = Files.createTempFile("h2-generated-", ".sql");
            try {
                ExportService.getInstance().exportPersisted(conn, config, schema, "records_%", null, "sql", file.toFile());
                String inserts = Files.readString(file); assertFalse(inserts.contains("DOUBLED"));
                ddl.dropTable(conn, config, schema, "records_%"); sql(conn, nativeDdl); sql(conn, inserts);
                data.insertRow(conn, config, schema, "records_%", Map.of());
                assertEquals(2L, ((Number)scalar(conn, "SELECT MAX(ID) FROM " + target)).longValue());
                assertThrows(Exception.class, () -> data.insertRow(conn, config, schema, "records_%", Map.of("VALUE_TXT", "blocked")));
                assertEquals(2, data.countRows(conn, config, schema, "records_%", null));
                String view=DdlService.formatTable(config,schema,"view_%");
                sql(conn,"CREATE VIEW "+view+" AS SELECT VALUE_TXT FROM "+target);
                var viewMetadata=MetadataService.getInstance().getTables(conn,config,schema).stream().filter(t->t.getName().equals("view_%")).findFirst().orElseThrow();
                assertTrue(viewMetadata.isView());
                String viewDdl=ddl.getCreateTableStatement(conn,config,schema,viewMetadata);
                sql(conn,"DROP VIEW "+view); sql(conn,viewDdl);
                assertEquals(2,data.countRows(conn,config,schema,"view_%",null));
            } finally { Files.deleteIfExists(file); }
        });
    }
    public static void transactionFlow(ConnectionConfig config) throws Exception {
        scoped(config, (conn, schema) -> {
            var data = DataService.getInstance(); var ddl = DdlService.getInstance();
            ddl.createTable(conn, config, schema, "ITEMS", List.of(new ColumnDefinition("ID","INT",0,false,true,false,""),new ColumnDefinition("TXT","VARCHAR",30,true,false,false,"")));
            data.insertRow(conn, config, schema, "ITEMS", Map.of("ID",1,"TXT","keep"));
            assertThrows(Exception.class, () -> data.commitChanges(conn,config,schema,"ITEMS",List.of(Map.of("ID",2),Map.of("ID",1)),List.of()));
            assertEquals(1,data.countRows(conn,config,schema,"ITEMS",null));
            conn.setAutoCommit(false);
            data.insertRow(conn,config,schema,"ITEMS",Map.of("ID",3));
            assertThrows(SQLException.class, () -> data.commitChanges(conn,config,schema,"ITEMS",List.of(Map.of("ID",4)),List.of()));
            assertEquals(2,data.countRows(conn,config,schema,"ITEMS",null));
            assertFalse(conn.getAutoCommit()); conn.rollback(); conn.setAutoCommit(true);
            assertEquals(1,data.countRows(conn,config,schema,"ITEMS",null));
            data.insertRow(conn,config,schema,"ITEMS",Map.of("ID",5));
            assertThrows(Exception.class, () -> ddl.alterTableModifyColumn(conn,config,schema,"ITEMS",new ColumnDefinition("TXT","VARCHAR",30,false,false,false,"")));
            assertTrue(MetadataService.getInstance().getColumns(conn,config,schema,"ITEMS").stream().filter(c->c.getName().equals("TXT")).findFirst().orElseThrow().isNullable());
            assertEquals("keep",scalar(conn,"SELECT TXT FROM " + DdlService.formatTable(config,schema,"ITEMS") + " WHERE ID=1"));
        });
    }
    public static void cancellationFlow(ConnectionConfig config) throws Exception {
        var manager = new DatabaseConnectionManager(); var worker=Executors.newSingleThreadExecutor();
        try (var session=manager.createSession(config)) {
            String longSql = "SELECT SUM(SIN(X)) FROM SYSTEM_RANGE(1,1000000000)";
            var beforeStart = new QueryExecution();
            beforeStart.requestCancellation();
            assertTrue(session.executeQuery("PUBLIC", longSql, new DataService.QueryOptions(5,15,5), beforeStart).isCancelled());
            assertFalse(beforeStart.hasStartedSql(), "An early cancellation must prevent SQL submission");

            int sessionId = session.execute(c -> ((Number)scalar(c, "SELECT SESSION_ID()")).intValue());
            var execution = new QueryExecution();
            var task = worker.submit(() -> session.executeQuery("PUBLIC", longSql, new DataService.QueryOptions(5,15,5), execution));
            // A client attachment is not evidence that an SSL server is executing SQL.
            // Observe this session separately before measuring active cancellation.
            try (var observer = manager.openConnection(config);
                 var statement = observer.prepareStatement("SELECT EXECUTING_STATEMENT FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID=?")) {
                statement.setInt(1, sessionId);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean running;
                do {
                    try (var rows = statement.executeQuery()) {
                        running = rows.next() && longSql.equals(rows.getString(1));
                    }
                    if (!running) Thread.sleep(10);
                } while (!running && !task.isDone() && System.nanoTime() < deadline);
                assertTrue(running, "The fixture server must be executing the query before cancellation");
                execution.cancel();
                // QueryOptions sets a 15-second server-side safety timeout. Allow it to
                // return if the remote TLS cancellation request is delayed.
                assertTrue(task.get(20, TimeUnit.SECONDS).isCancelled());
            }
            var recovered = session.execute(c -> DataService.getInstance().executeQuery(c, "PUBLIC", "SELECT 1"));
            assertFalse(recovered.hasError(), recovered.getError());
            var timeout=session.execute(c->DataService.getInstance().executeQuery(c,"PUBLIC","SELECT SUM(SIN(X)) FROM SYSTEM_RANGE(1,1000000000)",new DataService.QueryOptions(5,1,5),new QueryExecution()));
            assertTrue(timeout.hasError() || timeout.isCancelled(),"Long query must respect its one second timeout");
            assertFalse(session.execute(c->DataService.getInstance().executeQuery(c,"PUBLIC","SELECT 1")).hasError());
        } finally { worker.shutdownNow(); manager.dispose(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    interface Flow { void run(Connection conn,String schema) throws Exception; }
    private static void scoped(ConnectionConfig config, Flow flow) throws Exception {
        var manager=new DatabaseConnectionManager(); String schema="reg_%_"+UUID.randomUUID().toString().replace("-", "");
        try(var conn=manager.openConnection(config)) {
            DdlService.getInstance().createDatabase(conn,config,schema);
            try { flow.run(conn,schema); }
            finally { conn.rollback(); conn.setAutoCommit(true); DdlService.getInstance().dropDatabase(conn,config,schema); }
        } finally { manager.dispose(); }
    }
    private static Object scalar(Connection conn,String sql) throws Exception { try(var stmt=conn.createStatement(); var rows=stmt.executeQuery(sql)) { assertTrue(rows.next()); return rows.getObject(1); } }
    private static void sql(Connection conn,String sql) throws Exception { try(var stmt=conn.createStatement()) { stmt.execute(sql); } }
}
