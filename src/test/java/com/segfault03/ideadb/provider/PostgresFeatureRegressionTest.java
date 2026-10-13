package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration") @Tag("postgres")
public class PostgresFeatureRegressionTest {
    public static ConnectionConfig config() {
        var config = new ConnectionConfig(DatabaseType.POSTGRESQL,"PostgreSQL regression");
        config.setPort(Integer.getInteger("lattice.test.postgres.port",5432));
        config.setPassword(System.getProperty("lattice.test.postgres.password","lattice-test"));
        return config;
    }
    @Test void catalogCreateAndSequenceStateReplay() throws Exception { catalogFlow(config()); }
    @Test void typedValuesAndEveryExportRoundTrip() throws Exception { valuesFlow(config()); }
    @Test void failedAlterationsAndCancellationPreserveSessionRecovery() throws Exception { sessionFlow(config()); }
    @Test void cancellationAtStartupNeverEscapesToTheNextQuery() throws Exception {
        var manager=new DatabaseConnectionManager(); var worker=Executors.newSingleThreadExecutor();
        try(var session=manager.createSession(config())) {
            for(int i=0;i<12;i++) {
                var execution=new QueryExecution();
                var task=worker.submit(()->session.execute(c->DataService.getInstance().executeQuery(c,"public","SELECT pg_sleep(20)",new DataService.QueryOptions(5,30,5),execution)));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!execution.isRunning() && System.nanoTime()<deadline) Thread.onSpinWait();
                assertTrue(execution.isRunning()); execution.cancel(); assertTrue(task.get(5,TimeUnit.SECONDS).isCancelled());
                assertFalse(session.execute(c->DataService.getInstance().executeQuery(c,"public","SELECT 1")).hasError());
            }
        } finally { worker.shutdownNow(); manager.dispose(); }
    }
    @Test void authenticationAndCustomConnectionOptions() throws Exception {
        var owner = new DatabaseConnectionManager(); var source = config();
        try {
            assertTrue(owner.testConnection(source).isSuccess());
            var missing=source.copy(); missing.setUser("lattice_nonexistent_" + UUID.randomUUID().toString().replace("-","")); assertFalse(owner.testConnection(missing).isSuccess());
            if (Boolean.getBoolean("lattice.test.postgres.authRequired")) {
                var wrong=source.copy(); wrong.setPassword("wrong-fixture-password"); var result=owner.testConnection(wrong);
                assertFalse(result.isSuccess()); assertFalse(result.getErrorMessage().contains("wrong-fixture-password"));
            }
            source.setCustomUrl("jdbc:postgresql://127.0.0.1:" + source.getPort() + "/postgres?ApplicationName=LatticeRegression&sslmode=disable&connectTimeout=5");
            try (var c=owner.openConnection(source)) { assertEquals("LatticeRegression",scalar(c,"SHOW application_name")); }
        } finally { owner.dispose(); }
    }
    public static void catalogFlow(ConnectionConfig config) throws Exception {
        scoped(config,(c,schema) -> {
            var ddl=DdlService.getInstance(); var data=DataService.getInstance(); String target=DdlService.formatTable(config,schema,"Records_%");
            ddl.createTable(c,config,schema,"Records_%",List.of(new ColumnDefinition("Id","BIGINT",0,false,true,true,""),new ColumnDefinition("Payload","VARCHAR",80,true,false,false,"TEXT:O'Brien\\path")));
            sql(c,"ALTER TABLE " + target + " ADD CONSTRAINT payload_check CHECK (\"Payload\" <> 'blocked')");
            sql(c,"CREATE INDEX \"Payload_index\" ON " + target + "(\"Payload\")");
            sql(c,"COMMENT ON COLUMN " + target + ".\"Payload\" IS E'comment\\nsecond line'");
            data.insertRow(c,config,schema,"Records_%",Map.of());
            String create=ddl.getCreateTableStatement(c,config,schema,new TableMetadata(null,schema,"Records_%","TABLE"));
            assertFalse(create.contains("Partial reconstruction")); assertTrue(create.contains("payload_check")); assertTrue(create.contains("Payload_index"));
            ddl.dropTable(c,config,schema,"Records_%"); sql(c,create);
            data.insertRow(c,config,schema,"Records_%",Map.of());
            assertEquals(2L,((Number)scalar(c,"SELECT \"Id\" FROM " + target)).longValue());
            assertEquals("O'Brien\\path",scalar(c,"SELECT \"Payload\" FROM " + target));
            assertThrows(SQLException.class,()->data.insertRow(c,config,schema,"Records_%",Map.of("Payload","blocked")));
            assertEquals(2,MetadataService.getInstance().getColumns(c,config,schema,"Records_%").size());
            var changed=new ColumnDefinition("Payload","VARCHAR",100,false,false,false,"TEXT:new\\value");
            ddl.alterTableModifyColumn(c,config,schema,"Records_%",changed);
            data.insertRow(c,config,schema,"Records_%",Map.of());
            assertEquals("new\\value",scalar(c,"SELECT \"Payload\" FROM " + target + " WHERE \"Id\"=4"));
            if(c.getMetaData().getDatabaseMajorVersion()>=10) {
                String generated = c.getMetaData().getDatabaseMajorVersion()>=12 ? ", doubled BIGINT GENERATED ALWAYS AS (id*2) STORED" : "";
                String identity=DdlService.formatTable(config,schema,"identity_table");
                sql(c,"CREATE TABLE " + identity + " (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,payload TEXT" + generated + ")");
                data.commitChanges(c,config,schema,"identity_table",List.of(Map.of("id",40L,"payload","manual")),List.of());
                data.insertRow(c,config,schema,"identity_table",Map.of("payload","auto"));
                var columns=MetadataService.getInstance().getColumns(c,config,schema,"identity_table");
                assertTrue(columns.getFirst().isAutoIncrement());
                if(!generated.isEmpty()) assertTrue(columns.stream().anyMatch(ColumnMetadata::isGenerated));
                Path file=Files.createTempFile("pg-identity", ".sql");
                try {
                    assertEquals(2,ExportService.getInstance().exportPersisted(c,config,schema,"identity_table",null,"sql",file.toFile()));
                    String inserts=Files.readString(file); assertFalse(inserts.contains("\"doubled\"")); assertTrue(inserts.contains("OVERRIDING SYSTEM VALUE"));
                    String definition=ddl.getCreateTableStatement(c,config,schema,new TableMetadata(null,schema,"identity_table","TABLE"));
                    ddl.dropTable(c,config,schema,"identity_table"); sql(c,definition); sql(c,inserts);
                    assertEquals(2,data.countRows(c,config,schema,"identity_table",null));
                    assertEquals(40L,((Number)scalar(c,"SELECT max(id) FROM " + identity)).longValue());
                } finally { Files.deleteIfExists(file); }
            }
        });
    }
    public static void valuesFlow(ConnectionConfig config) throws Exception {
        scoped(config,(c,schema) -> {
            String target=DdlService.formatTable(config,schema,"values_table"); var data=DataService.getInstance(); int major=c.getMetaData().getDatabaseMajorVersion();
            sql(c,"CREATE TABLE " + target + " (id INTEGER PRIMARY KEY, txt TEXT, bytes BYTEA, ts TIMESTAMP(6), tz TIMESTAMP(6) WITH TIME ZONE, amount NUMERIC, bits BIT(5), nums INTEGER[], token UUID" + (major>=9 && (major>9 || c.getMetaData().getDatabaseMinorVersion()>=4) ? ", document JSONB" : "") + ")");
            var values = new LinkedHashMap<String,Object>(); values.put("id",1); values.put("txt","O'Brien\\path\nemoji 😀"); values.put("bytes",new byte[]{0,1,(byte)255});
            values.put("ts","2026-10-13 10:20:30.123456"); values.put("tz","2026-10-13 11:20:30.123456+01"); values.put("amount",new java.math.BigDecimal("12345678901234567890.123456"));
            values.put("bits","10101"); values.put("nums","{1,2,3}"); values.put("token","dd39038f-081d-477a-9153-d931c322d331");
            if(major>9 || major==9 && c.getMetaData().getDatabaseMinorVersion()>=4) values.put("document","{\"quoted\":\"O'Brien\",\"number\":2}");
            data.commitChanges(c,config,schema,"values_table",List.of(values),List.of());
            sql(c,"INSERT INTO " + target + " (id,ts,tz,amount) VALUES(2,'infinity','-infinity','NaN')");
            var original=data.fetchData(c,config,schema,"values_table",null,null,10,0); assertFalse(original.hasError(),original.getError());
            var columns=MetadataService.getInstance().getColumns(c,config,schema,"values_table");
            assertEquals("TIMESTAMP WITH TIME ZONE",columns.stream().filter(col->col.getName().equals("tz")).findFirst().orElseThrow().getFormattedType());
            assertEquals("NUMERIC",columns.stream().filter(col->col.getName().equals("amount")).findFirst().orElseThrow().getFormattedType());
            for(String format:List.of("sql","csv","json")) {
                Path output=Files.createTempFile("pg-values", "."+format);
                try {
                    assertEquals(2,ExportService.getInstance().exportPersisted(c,config,schema,"values_table",null,format,output.toFile()));
                    assertTrue(c.getAutoCommit(),"Export must restore its connection state");
                    String exported=Files.readString(output); assertFalse(exported.isBlank());
                    if(format.equals("sql")) {
                        DdlService.getInstance().truncateTable(c,config,schema,"values_table"); sql(c,exported);
                        var replay=data.fetchData(c,config,schema,"values_table",null,null,10,0);
                        for(int row=0;row<2;row++) for(int col=0;col<original.getColumnNames().size();col++) {
                            Object expected=original.getRows().get(row).get(col), actual=replay.getRows().get(row).get(col);
                            if(expected instanceof byte[] bytes) assertArrayEquals(bytes,(byte[])actual); else assertEquals(expected,actual,original.getColumnNames().get(col));
                        }
                    }
                } finally { Files.deleteIfExists(output); }
            }
            c.setAutoCommit(false);
            Path snapshot=Files.createTempFile("pg-pinned", ".csv");
            try { ExportService.getInstance().exportPersisted(c,config,schema,"values_table",null,"csv",snapshot.toFile()); assertFalse(c.getAutoCommit()); }
            finally { c.rollback(); c.setAutoCommit(true); Files.deleteIfExists(snapshot); }
        });
    }
    public static void sessionFlow(ConnectionConfig config) throws Exception {
        scoped(config,(c,schema) -> {
            var ddl=DdlService.getInstance(); var data=DataService.getInstance(); String table=DdlService.formatTable(config,schema,"alter_table");
            ddl.createTable(c,config,schema,"alter_table",List.of(new ColumnDefinition("id","INTEGER",0,false,true,false,""),new ColumnDefinition("note","VARCHAR",20,true,false,false,"TEXT:old")));
            sql(c,"INSERT INTO " + table + " VALUES(1,NULL)");
            assertThrows(Exception.class,()->ddl.alterTableModifyColumn(c,config,schema,"alter_table",new ColumnDefinition("note","VARCHAR",50,false,false,false,"TEXT:new")));
            var original=MetadataService.getInstance().getColumns(c,config,schema,"alter_table").stream().filter(col->col.getName().equals("note")).findFirst().orElseThrow();
            assertEquals(20,original.getColumnSize()); assertTrue(original.isNullable()); assertTrue(original.getDefaultValue().contains("old"));
            c.setAutoCommit(false);
            sql(c,"INSERT INTO " + table + " VALUES(2,'pending')");
            assertThrows(Exception.class,()->ddl.alterTableModifyColumn(c,config,schema,"alter_table",new ColumnDefinition("note","VARCHAR",50,false,false,false,"TEXT:new")));
            assertEquals(2,data.countRows(c,config,schema,"alter_table",null)); c.rollback(); c.setAutoCommit(true);
            var manager=new DatabaseConnectionManager();
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try(var session=manager.createSession(config)) {
                QueryExecution execution=new QueryExecution();
                Future<QueryResult> running=worker.submit(()->session.execute(conn->data.executeQuery(conn,schema,"SELECT pg_sleep(20)",new DataService.QueryOptions(10,30,10),execution)));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!execution.isRunning() && System.nanoTime()<deadline) Thread.onSpinWait();
                assertTrue(execution.isRunning()); execution.cancel(); assertTrue(running.get(5,TimeUnit.SECONDS).isCancelled());
                assertFalse(session.discardInvalidConnection()); assertFalse(session.execute(conn->data.executeQuery(conn,schema,"SELECT 1")).hasError());
                session.execute(conn->{conn.setAutoCommit(false);return null;});
                assertTrue(session.execute(conn->data.executeQuery(conn,schema,"SELECT 1/0")).hasError());
                assertFalse(session.execute(conn->data.executeQuery(conn,schema,"-- recover the transaction\nROLLBACK")).hasError());
                assertFalse(session.execute(conn->data.executeQuery(conn,schema,"SELECT 1")).hasError());
                session.execute(conn->{conn.rollback();return null;});
            } finally { worker.shutdownNow(); manager.dispose(); }
        });
    }
    @FunctionalInterface interface Flow { void run(Connection c,String schema) throws Exception; }
    static void scoped(ConnectionConfig config,Flow flow) throws Exception {
        var owner=new DatabaseConnectionManager(); String schema="lattice_pg_"+UUID.randomUUID().toString().replace("-","");
        try(var c=owner.openConnection(config)) {
            DdlService.getInstance().createDatabase(c,config,schema);
            try { flow.run(c,schema); } finally { if(!c.getAutoCommit()) c.rollback(); DdlService.getInstance().dropDatabase(c,config,schema); }
        } finally { owner.dispose(); }
    }
    static void sql(Connection c,String sql) throws SQLException { try(var statement=c.createStatement()) { statement.execute(sql); } }
    static Object scalar(Connection c,String sql) throws SQLException { try(var statement=c.createStatement(); var rows=statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getObject(1); } }
}
