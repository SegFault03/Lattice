package com.segfault03.ideadb;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
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
    private static void lifecycle(ConnectionConfig source) throws Exception {
        var owned=new DatabaseConnectionManager(); var config=source.copy(); config.setId(UUID.randomUUID().toString());
        Connection browser=owned.getConnection(config);
        try(var session=owned.createSession(config)) {
            Connection console=session.execute(c -> c); owned.dispose();
            check(browser.isClosed() && console.isClosed(),"Application service disposal must close owned sessions");
            try { owned.getConnection(config); throw new AssertionError("Disposed manager reopened"); } catch(SQLException expected) { check(true,"Disposed manager rejects work"); }
        }
        var executor=Executors.newSingleThreadExecutor(); var scope=new DatabaseTaskScope(executor);
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1); java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
        try {
            Future<?> running=scope.submitMutation(() -> { started.countDown(); try { release.await(); writes.incrementAndGet(); } catch(InterruptedException error) { throw new RuntimeException(error); } });
            check(started.await(2,TimeUnit.SECONDS),"Mutation worker starts");
            Future<?> queued=scope.submitMutation(writes::incrementAndGet);
            var read=scope.openRead(source); scope.close();
            check(read.connection().isClosed() && queued.isCancelled() && !running.isCancelled(),"Scope disposal closes reads, cancels queued writes and preserves running atomic writes");
            release.countDown(); running.get(2,TimeUnit.SECONDS);
            check(writes.get()==1,"Disposed scope must not execute queued writes");
        } finally { release.countDown(); scope.close(); executor.shutdownNow(); }
    }
    private static void configurationLifecycle(ConnectionConfig source) throws Exception {
        ConnectionConfig original=source.copy(); original.setId(UUID.randomUUID().toString());
        manager.registerConfiguration(original);
        Connection first=manager.getConnection(original);
        try (DatabaseSession staleSession=manager.createSession(original)) {
            Connection sessionConnection=staleSession.execute(c -> c);
            ConnectionConfig changed=original.copy(); changed.setAutoCommit(false);
            manager.registerConfiguration(changed);
            check(first.isClosed() && sessionConnection.isClosed(),"Settings changes must retire browser and console connections");
            try { manager.getConnection(original); throw new AssertionError("Stale browser resurrected old settings"); } catch (SQLException expected) { check(expected.getMessage().contains("settings changed"),"Stale browser must report reopen guidance"); }
            try { staleSession.execute(c -> c); throw new AssertionError("Stale session resurrected old settings"); } catch (SQLException expected) { check(true,"Stale console rejected"); }
            try { manager.openConnection(original); throw new AssertionError("Stale grid mutation accepted"); } catch (SQLException expected) { check(true,"Stale grid mutation rejected"); }
            Connection fresh=manager.getConnection(changed);
            check(!fresh.getAutoCommit(),"Fresh editors must use updated settings");
            manager.removeConfiguration(changed.getId());
            check(fresh.isClosed(),"Removing a connection retires all sessions");
            try { manager.getConnection(changed); throw new AssertionError("Removed connection resurrected"); } catch (SQLException expected) { check(expected.getMessage().contains("removed"),"Removed editors must be rejected"); }
        } finally { manager.closeConnection(original.getId()); }
    }
    private static void runEngine(ConnectionConfig config) throws Exception {
        String schema = "LATTICE_FIX_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        Connection connection = manager.getConnection(config);
        ddl.createDatabase(connection, config, schema);
        try {
            lifecycle(config);
            sessions(config, schema);
            configurationLifecycle(config);
            queryExecution(config, schema);
            metadata(config, schema);
            quotedIdentifiers(config, schema);
            pagination(config, schema);
            valueConversion(config, schema);
            defaultRows(config, schema);
            mutations(config, schema);
            columnAlterations(config, schema);
            schemaExports(config, schema);
            restrictiveDrop(config, schema);
            dataExports(config, schema);
        } finally {
            if (config.getType() == DatabaseType.MYSQL) connection.setCatalog("shop_db");
            else connection.setSchema("PUBLIC");
            ddl.dropDatabase(connection, config, schema);
        }
        System.out.println("PASS " + config.getType() + " functional regressions (temporary schema removed)");
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
    }
    private static void queryExecution(ConnectionConfig config, String schema) throws Exception {
        try(Connection c=manager.openConnection(config)) {
            String table=DdlService.formatTable(config,schema,"QUERY_ROWS");
            sql(c,"CREATE TABLE " + table + " (ID INT)");
            try(PreparedStatement insert=c.prepareStatement("INSERT INTO " + table + " VALUES (?)")) {
                for(int i=0;i<200;i++) { insert.setInt(1,i); insert.addBatch(); } insert.executeBatch();
            }
            java.io.File full=java.io.File.createTempFile("lattice-full-export-",".json"); full.deleteOnExit();
            long exported=ExportService.getInstance().exportPersisted(c,config,schema,"QUERY_ROWS",null,"json",full);
            check(exported==200 && com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(full.toPath())).getAsJsonArray().size()==200,"Persisted export must stream the complete table beyond page limits");
            check(ExportService.getInstance().exportPersisted(c,config,schema,"QUERY_ROWS","ID<12","csv",full)==12 && java.nio.file.Files.readAllLines(full.toPath()).size()==13,"Persisted export must respect applied filters and include CSV header");
            var options=new DataService.QueryOptions(25,5,10);
            QueryResult capped=data.executeQuery(c,schema,"SELECT * FROM " + table,options,new QueryExecution());
            check(!capped.hasError() && capped.getRows().size()==25 && capped.isTruncated() && capped.getMessage().contains("omitted"),"Query row limit must report truncation");
            QueryResult exact=data.executeQuery(c,schema,"SELECT * FROM " + table + " WHERE ID<25",options,new QueryExecution());
            check(exact.getRows().size()==25 && !exact.isTruncated(),"An exact-size result must not be marked truncated");
            var cancelled=new QueryExecution(); cancelled.cancel();
            QueryResult beforeExecution=data.executeQuery(c,schema,"SELECT * FROM " + table,options,cancelled);
            check(beforeExecution.isCancelled() && !beforeExecution.hasError(),"Cancellation before execution must stop the query as a neutral outcome");
            Connection delayed=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args) -> {
                Object value=invoke(c,method,args);
                if(!method.getName().equals("createStatement")) return value;
                return java.lang.reflect.Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(sp,sm,sa) -> {
                    Object result=invoke(value,sm,sa);
                    if(!sm.getName().equals("getResultSet")) return result;
                    return java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(rp,rm,ra) -> { if(rm.getName().equals("next")) Thread.sleep(30); return invoke(result,rm,ra); });
                });
            });
            QueryResult timed=data.executeQuery(delayed,schema,"SELECT * FROM " + table + " WHERE ID<3",options,new QueryExecution());
            check(!timed.hasError() && timed.getExecutionTimeMs()>=100,"Elapsed time must include result fetching");
            var active=new QueryExecution(); ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                Future<QueryResult> pending=executor.submit(() -> data.executeQuery(delayed,schema,"SELECT * FROM " + table,options,active));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!active.isRunning() && !pending.isDone() && System.nanoTime()<deadline) Thread.sleep(5);
                Thread.sleep(50); active.cancel();
                QueryResult result=pending.get(3,TimeUnit.SECONDS);
                check(result.isCancelled() && !result.hasError() && !active.isRunning(),"In-flight cancellation must produce a neutral outcome and detach the statement");
            } finally { executor.shutdownNow(); }
            if(config.getType()==DatabaseType.MYSQL) {
                QueryResult timeout=data.executeQuery(c,schema,"SELECT SLEEP(2)",new DataService.QueryOptions(25,1,10),new QueryExecution());
                check(timeout.hasError() && timeout.getExecutionTimeMs()<4000,"Query timeout must interrupt a running MySQL statement");
            }
        }
    }
    private static void metadata(ConnectionConfig config, String schema) throws Exception {
        try(Connection c=manager.openConnection(config)) {
            sql(c,"CREATE TABLE " + DdlService.formatTable(config,schema,"META_A") + " (ID INT PRIMARY KEY, EXACT_COL INT)");
            sql(c,"CREATE TABLE " + DdlService.formatTable(config,schema,"METAXA") + " (WRONG_COL INT)");
            var columns=MetadataService.getInstance().getColumns(c,config,schema,"META_A");
            check(columns.size()==2 && columns.get(0).isPrimaryKey() && columns.stream().noneMatch(col -> col.getName().equals("WRONG_COL")),"Underscore metadata lookup must target only the exact table");
            String quote = config.getType()==DatabaseType.MYSQL ? "`" : "\"";
            sql(c,"CREATE TABLE " + quote + schema + quote + "." + quote + "Meta%Case" + quote + " (EXACT_COL INT)");
            var percent=MetadataService.getInstance().getColumns(c,config,schema,"Meta%Case");
            check(percent.size()==1 && percent.get(0).getName().equals("EXACT_COL"),"Percent and mixed-case metadata names must remain literal");
            var broken = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args) -> { throw new SQLException("metadata unavailable"); });
            try { MetadataService.getInstance().getColumns(broken,config,schema,"META_A"); throw new AssertionError("Metadata errors swallowed"); }
            catch(SQLException expected) { check(expected.getMessage().equals("metadata unavailable"),"Metadata failures must reach callers"); }
        }
    }
    private static void quotedIdentifiers(ConnectionConfig config,String schema) throws Exception {
        try(Connection c=manager.openConnection(config)) {
            String table="Case Table" + (config.getType()==DatabaseType.MYSQL ? "`" : "\"");
            String value="value " + (config.getType()==DatabaseType.MYSQL ? "`" : "\"");
            ddl.createTable(c,config,schema,table,List.of(new ColumnDefinition("select","INT",0,false,true,false,""),new ColumnDefinition(value,"VARCHAR",100,true,false,false,"")));
            data.insertRow(c,config,schema,table,Map.of("select",1,value,"before"));
            QueryResult result=data.fetchData(c,config,schema,table,null,null,10,0);
            check(!result.hasError() && result.getRows().size()==1,"Quoted table and reserved column must support fetch");
            data.updateRow(c,config,schema,table,Map.of(value,"after"),Map.of("select",1));
            check("after".equals(scalar(c,ddl.buildSelectSql(config,schema,table,List.of(value)))),"Generated SELECT must escape embedded identifier quotes");
            ddl.alterTableRenameColumn(c,config,schema,table,value,"new value");
            ddl.alterTableAddColumn(c,config,schema,table,new ColumnDefinition("extra value","INT",0,true,false,false,""));
            check(MetadataService.getInstance().getColumns(c,config,schema,table).size()==3,"Quoted identifiers must support add/rename DDL");
            String renamed="Renamed Table";
            TableMetadata renamedMetadata=ddl.alterTableRename(c,config,schema,table,renamed);
            var originalMetadata=new TableMetadata(null,schema,table,"TABLE");
            var oldFile=new com.segfault03.ideadb.editor.TableDataVirtualFile(config,schema,originalMetadata);
            var newFile=new com.segfault03.ideadb.editor.TableDataVirtualFile(config,schema,renamedMetadata);
            check(renamedMetadata.getName().equalsIgnoreCase(renamed) && !renamedMetadata.getColumns().isEmpty() && !oldFile.getFileKey().equals(newFile.getFileKey()) && newFile.getName().contains(renamedMetadata.getName()),"Rename must return current metadata and new editor identity/title");
            data.deleteRow(c,config,schema,renamed,Map.of("select",1));
            check(data.countRows(c,config,schema,renamed,null)==0,"Quoted identifiers must support rename/delete");
            ddl.alterTableDropColumn(c,config,schema,renamed,"extra value");
            ddl.dropTable(c,config,schema,renamed);
        }
    }
    private static void pagination(ConnectionConfig config,String schema) throws Exception {
        try(Connection c=manager.openConnection(config)) {
            String table=DdlService.formatTable(config,schema,"PAGE_ROWS"); sql(c,"CREATE TABLE " + table + " (ID INT PRIMARY KEY, TENANT INT)");
            for(int id:new int[]{9,2,7,1,5}) data.insertRow(c,config,schema,"PAGE_ROWS",Map.of("ID",id,"TENANT",0));
            List<Integer> ids=new ArrayList<>();
            for(int offset=0;offset<5;offset+=2) {
                QueryResult page=data.fetchData(c,config,schema,"PAGE_ROWS",null,null,2,offset);
                check(!page.hasError(),"Ordered page fetch succeeds");
                for(List<Object> row:page.getRows()) ids.add(((Number)row.get(0)).intValue());
            }
            check(ids.equals(List.of(1,2,5,7,9)),"Default pagination must order by the unique primary key");
            QueryResult descending=data.fetchData(c,config,schema,"PAGE_ROWS",null,"ID DESC",2,0);
            check(((Number)descending.getRows().get(0).get(0)).intValue()==9,"Explicit sorting must override default ordering");
            check(data.countRows(c,config,schema,"PAGE_ROWS",null)==5L,"Row count must return long");
            var seen=new java.util.concurrent.atomic.AtomicBoolean();
            var result=(ResultSet)java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class[]{ResultSet.class},(proxy,method,args) -> switch(method.getName()) { case "next" -> !seen.getAndSet(true); case "getLong" -> 3_000_000_000L; case "close" -> null; default -> throw new AssertionError("Unexpected count call " + method.getName()); });
            var statement=(Statement)java.lang.reflect.Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class[]{Statement.class},(proxy,method,args) -> switch(method.getName()) { case "executeQuery" -> result; case "setQueryTimeout","close" -> null; default -> throw new AssertionError("Unexpected statement call"); });
            var huge=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args) -> statement);
            check(data.countRows(huge,config,schema,"PAGE_ROWS",null)==3_000_000_000L,"Count must preserve values beyond Integer.MAX_VALUE");
            try { data.countRows(c,config,schema,"PAGE_ROWS","missing_column=1"); throw new AssertionError("Count failure swallowed"); } catch(SQLException expected) { check(true,"Count errors must be visible"); }
        }
    }
    private static void valueConversion(ConnectionConfig config,String schema) throws Exception {
        try(Connection c=manager.openConnection(config)) {
            String table=DdlService.formatTable(config,schema,"VALUE_INPUT");
            if(config.getType()==DatabaseType.MYSQL) sql(c,"CREATE TABLE " + table + " (ID INT PRIMARY KEY, U32 INT UNSIGNED, U64 BIGINT UNSIGNED, CLOCK TIME(6), MOMENT DATETIME(6), AMOUNT DECIMAL(5,2), SIGNED_BYTE TINYINT)");
            else sql(c,"CREATE TABLE " + table + " (ID INT PRIMARY KEY, U32 BIGINT, U64 DECIMAL(20,0), CLOCK TIME(6), MOMENT TIMESTAMP(6), AMOUNT DECIMAL(5,2), SIGNED_BYTE TINYINT)");
            var columns=MetadataService.getInstance().getColumns(c,config,schema,"VALUE_INPUT");
            var raw=Map.of("ID","1","U32","4294967295","U64","18446744073709551615","CLOCK",config.getType()==DatabaseType.MYSQL ? "-12:34:56.123456" : "12:34:56.123456","MOMENT","2026-10-04 12:34:56.123456","AMOUNT","123.4500","SIGNED_BYTE","127");
            var converted=new LinkedHashMap<String,Object>();
            for(ColumnMetadata column:columns) {
                String input=raw.get(column.getName());
                check(CellValueConverter.validate(config.getType(),column,input)==null,"Valid engine input rejected: " + column.getName() + " " + column.getTypeName() + " scale=" + column.getDecimalDigits() + ": " + CellValueConverter.validate(config.getType(),column,input));
                converted.put(column.getName(),CellValueConverter.convert(config.getType(),column,input));
            }
            data.insertRow(c,config,schema,"VALUE_INPUT",converted);
            check(((Number)scalar(c,"SELECT U32 FROM " + table)).longValue()==4294967295L,"Unsigned INT boundary must bind without overflow");
            check(scalar(c,"SELECT U64 FROM " + table).toString().equals("18446744073709551615"),"Unsigned BIGINT boundary must bind without overflow");
            check(scalar(c,"SELECT CAST(CLOCK AS CHAR(30)) FROM " + table).toString().trim().equals(raw.get("CLOCK")),"TIME duration and microseconds must survive binding");
            String moment=config.getType()==DatabaseType.MYSQL ? "SELECT DATE_FORMAT(MOMENT,'%Y-%m-%d %H:%i:%s.%f') FROM " + table : "SELECT CAST(MOMENT AS VARCHAR(50)) FROM " + table;
            check(scalar(c,moment).toString().equals("2026-10-04 12:34:56.123456"),"Microsecond timestamp must survive binding");
            ColumnMetadata integer=columns.stream().filter(col -> col.getName().equals("SIGNED_BYTE")).findFirst().orElseThrow();
            check(CellValueConverter.validate(config.getType(),integer,"128")!=null,"Signed integer overflow must be rejected");
            ColumnMetadata amount=columns.stream().filter(col -> col.getName().equals("AMOUNT")).findFirst().orElseThrow();
            check(CellValueConverter.validate(config.getType(),amount,"1000.00")!=null && CellValueConverter.validate(config.getType(),amount,"1.234")!=null,"Decimal precision and scale must be enforced");
            check(CellValueConverter.validate(config.getType(),new ColumnMetadata("F","DOUBLE",Types.DOUBLE,64,0,true,false,false,null),"NaN")!=null,"Non-finite numeric input must be rejected");
            try { CellValueConverter.convert(config.getType(),integer,"bad"); throw new AssertionError("Invalid conversion returned the original text"); } catch(IllegalArgumentException expected) { check(true,"Conversion failure must be explicit"); }
        }
    }
    private static void defaultRows(ConnectionConfig config,String schema) throws Exception {
        try(Connection c=manager.openConnection(config)) {
            ddl.createTable(c,config,schema,"DEFAULT_ROWS",List.of(new ColumnDefinition("ID","BIGINT",0,false,true,true,""),new ColumnDefinition("REQUIRED","INT",0,false,false,false,"7"),new ColumnDefinition("OPTIONAL","VARCHAR",50,true,false,false,"preset")));
            var columns=MetadataService.getInstance().getColumns(c,config,schema,"DEFAULT_ROWS");
            ColumnMetadata required=columns.stream().filter(col -> col.getName().equals("REQUIRED")).findFirst().orElseThrow();
            Object initial=RowDefaults.initialValue(required);
            check(initial==RowDefaults.Value.USE_DEFAULT && com.segfault03.ideadb.ui.TableDataEditorPanel.validateCellValue(required,initial)==null,"Required defaults must initialize a valid explicit default state");
            var draft=com.segfault03.ideadb.state.TableDraftState.Draft.capture(List.of("REQUIRED"),List.of("INT"),List.of(),List.of(List.of(initial)));
            check(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(draft),com.segfault03.ideadb.state.TableDraftState.Draft.class).values().get(0).get(0)==initial,"Default-state drafts must round-trip");
            data.commitChanges(c,config,schema,"DEFAULT_ROWS",List.of(Map.of()),List.of());
            String table=DdlService.formatTable(config,schema,"DEFAULT_ROWS");
            check(((Number)scalar(c,"SELECT " + DdlService.quoteIdentifier(config,"REQUIRED") + " FROM " + table)).intValue()==7 && scalar(c,"SELECT OPTIONAL FROM " + table).equals("preset"),"Default-only row must insert and use required/nullable defaults");
            var explicitNull=new LinkedHashMap<String,Object>(); explicitNull.put("OPTIONAL",null);
            data.insertRow(c,config,schema,"DEFAULT_ROWS",explicitNull);
            check(((Number)scalar(c,"SELECT COUNT(*) FROM " + table + " WHERE OPTIONAL IS NULL")).intValue()==1,"Explicit NULL must remain distinct from omitted default");
            ddl.createTable(c,config,schema,"IDENTITY_ROWS",List.of(new ColumnDefinition("ID","BIGINT",0,false,true,true,"")));
            data.insertRow(c,config,schema,"IDENTITY_ROWS",Map.of());
            check(data.countRows(c,config,schema,"IDENTITY_ROWS",null)==1,"Identity-only row must insert");
        }
    }
    private static void dataExports(ConnectionConfig config,String schema) throws Exception {
        try(Connection c=manager.openConnection(config)) {
            String table=DdlService.formatTable(config,schema,"EXPORT_DATA");
            sql(c,"CREATE TABLE " + table + " (ID INT PRIMARY KEY, TEXT_VALUE VARCHAR(200), BYTES_VALUE VARBINARY(100), NULL_VALUE VARCHAR(10), ACTIVE BOOLEAN, MOMENT TIMESTAMP(6))");
            ColumnMetadata textColumn=new ColumnMetadata("TEXT_VALUE","VARCHAR",Types.VARCHAR,200,0,false,false,false,null);
            for(String text:List.of("null","<null>"," NULL ","")) {
                Object converted=com.segfault03.ideadb.ui.TableDataEditorPanel.parseTypedValue(textColumn,text);
                check(text.equals(converted) && com.segfault03.ideadb.ui.TableDataEditorPanel.validateCellValue(textColumn,text)==null,"Text entry must preserve NULL-like words and whitespace");
            }
            var values=new LinkedHashMap<String,Object>(); values.put("ID",1); values.put("TEXT_VALUE","O'Reilly C:\\new\\test \u0001 \uD83D\uDE80"); values.put("BYTES_VALUE",new byte[]{0,1,127,-1}); values.put("NULL_VALUE",null); values.put("ACTIVE",true); values.put("MOMENT",Timestamp.valueOf("2026-10-04 12:34:56.123456"));
            data.insertRow(c,config,schema,"EXPORT_DATA",values);
            QueryResult before=data.fetchData(c,config,schema,"EXPORT_DATA",null,null,10,0);
            java.io.File exported=java.io.File.createTempFile("lattice-export-",".sql"); exported.deleteOnExit();
            ExportService.getInstance().exportToSqlInsert(config,schema,"EXPORT_DATA",before,exported);
            sql(c,"DELETE FROM " + table);
            sql(c,java.nio.file.Files.readString(exported.toPath()));
            QueryResult after=data.fetchData(c,config,schema,"EXPORT_DATA",null,null,10,0);
            for(int col=0;col<before.getColumnNames().size();col++) check(Objects.deepEquals(before.getRows().get(0).get(col),after.getRows().get(0).get(col)),"SQL export must round-trip typed cell " + before.getColumnNames().get(col));
            java.io.File json=java.io.File.createTempFile("lattice-export-",".json"); json.deleteOnExit();
            ExportService.getInstance().exportToJson(before,json);
            var parsed=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(json.toPath())).getAsJsonArray().get(0).getAsJsonObject();
            check(parsed.get("TEXT_VALUE").getAsString().equals(values.get("TEXT_VALUE")),"JSON controls/unicode must parse and round-trip");
            check(parsed.get("BYTES_VALUE").getAsString().equals("base64:AAF//w=="),"JSON binary representation must be explicit");
            var nonfinite=QueryResult.forResultSet(List.of("VALUE"),List.of("DOUBLE"),List.of(List.of(Double.NaN)),0);
            ExportService.getInstance().exportToJson(nonfinite,json);
            check(com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(json.toPath())).getAsJsonArray().get(0).getAsJsonObject().get("VALUE").getAsString().equals("NaN"),"Non-finite JSON values must be valid strings");
            try { ExportService.getInstance().exportToJson(QueryResult.forResultSet(List.of("VALUE","VALUE"),List.of("INT","INT"),List.of(List.of(1,2)),0),json); throw new AssertionError("Duplicate labels accepted"); } catch(IllegalArgumentException expected) { check(true,"Duplicate JSON labels rejected"); }
        }
    }
    private static void restrictiveDrop(ConnectionConfig config,String schema) throws Exception {
        if(config.getType()!=DatabaseType.HSQLDB) return;
        try(Connection c=manager.openConnection(config)) {
            String parent=DdlService.formatTable(config,schema,"DROP_PARENT"), child=DdlService.formatTable(config,schema,"DROP_CHILD"), view=DdlService.formatTable(config,schema,"DROP_VIEW");
            sql(c,"CREATE TABLE " + parent + " (ID INT PRIMARY KEY)");
            sql(c,"CREATE TABLE " + child + " (ID INT REFERENCES " + parent + "(ID))");
            sql(c,"CREATE VIEW " + view + " AS SELECT * FROM " + parent);
            try { ddl.dropTable(c,config,schema,"DROP_PARENT"); throw new AssertionError("Implicit cascade accepted"); } catch(SQLException expected) { check(true,"Dependent table drop must be rejected"); }
            check(data.countRows(c,config,schema,"DROP_VIEW",null)==0,"Failed restrictive drop must preserve dependent view");
            try { sql(c,"INSERT INTO " + child + " VALUES (999)"); throw new AssertionError("Dependent foreign key removed"); } catch(SQLException expected) { check(true,"Dependent foreign key remains"); }
            sql(c,"DROP VIEW " + view); ddl.dropTable(c,config,schema,"DROP_CHILD"); ddl.dropTable(c,config,schema,"DROP_PARENT");
        }
    }
    private static void schemaExports(ConnectionConfig config,String schema) throws Exception {
        if(config.getType()!=DatabaseType.HSQLDB) return;
        try(Connection c=manager.openConnection(config)) {
            String table=DdlService.formatTable(config,schema,"DDL_EXPORT");
            String parent=DdlService.formatTable(config,schema,"DDL_PARENT");
            sql(c,"CREATE TABLE " + parent + " (ID BIGINT PRIMARY KEY)");
            sql(c,"CREATE TABLE " + table + " (ID BIGINT GENERATED BY DEFAULT AS IDENTITY (START WITH 100 INCREMENT BY 2) PRIMARY KEY, VALUE DOUBLE, PARENT_ID BIGINT REFERENCES " + parent + "(ID), UNIQUE(VALUE), CHECK(VALUE>0))");
            sql(c,"CREATE INDEX " + DdlService.quoteIdentifier(config,"DDL_VALUE_INDEX") + " ON " + table + " (VALUE)");
            sql(c,"INSERT INTO " + table + " (VALUE) VALUES (1.5)");
            var metadata=new TableMetadata(null,schema,"DDL_EXPORT","TABLE"); metadata.setColumns(MetadataService.getInstance().getColumns(c,config,schema,"DDL_EXPORT"));
            String exported=ddl.getCreateTableStatement(c,config,schema,metadata);
            check(!exported.contains("DOUBLE(64)") && exported.contains("CHECK") && exported.contains("UNIQUE") && exported.contains("CREATE INDEX"),"HSQLDB export must retain constraints and valid type syntax");
            String clone=schema + "_COPY"; ddl.createDatabase(c,config,clone);
            try {
                sql(c,"CREATE TABLE " + DdlService.formatTable(config,clone,"DDL_PARENT") + " (ID BIGINT PRIMARY KEY)");
                String recreated=exported.replace(schema + ".",clone + ".").replace("\"" + schema + "\".","\"" + clone + "\".");
                for(String command:recreated.split(";")) if(!command.isBlank()) sql(c,command);
                String copy=DdlService.formatTable(config,clone,"DDL_EXPORT");
                sql(c,"INSERT INTO " + copy + " (VALUE) VALUES (2.5)");
                check(((Number)scalar(c,"SELECT ID FROM " + copy)).longValue()==102,"Export must preserve current identity state and increment");
                try { sql(c,"INSERT INTO " + copy + " (VALUE) VALUES (2.5)"); throw new AssertionError("UNIQUE lost"); } catch(SQLException expected) { check(true,"UNIQUE preserved"); }
                try { sql(c,"INSERT INTO " + copy + " (VALUE,PARENT_ID) VALUES (3.5,999)"); throw new AssertionError("Foreign key lost"); } catch(SQLException expected) { check(true,"Foreign key preserved"); }
                try { sql(c,"INSERT INTO " + copy + " (VALUE) VALUES (-1)"); throw new AssertionError("CHECK lost"); } catch(SQLException expected) { check(true,"CHECK preserved"); }
            } finally { ddl.dropDatabase(c,config,clone); }
        }
    }
    private static void columnAlterations(ConnectionConfig config, String schema) throws Exception {
        try (Connection c = manager.openConnection(config)) {
            var literal=new ColumnDefinition("VALUE","VARCHAR",100,true,false,false,"NULL"); literal.setDefaultKind(ColumnDefinition.DefaultKind.LITERAL);
            var path=new ColumnDefinition("PATH","VARCHAR",100,true,false,false,"C:\\new\\test"); path.setDefaultKind(ColumnDefinition.DefaultKind.LITERAL);
            ddl.createTable(c,config,schema,"LITERAL_DEFAULTS",List.of(literal,path));
            String literals=DdlService.formatTable(config,schema,"LITERAL_DEFAULTS");
            sql(c,config.getType()==DatabaseType.MYSQL ? "INSERT INTO " + literals + " () VALUES ()" : "INSERT INTO " + literals + " DEFAULT VALUES");
            check("NULL".equals(scalar(c,"SELECT " + DdlService.quoteIdentifier(config,"VALUE") + " FROM " + literals)),"Explicit literal NULL default must remain text");
            check("C:\\new\\test".equals(scalar(c,"SELECT " + DdlService.quoteIdentifier(config,"PATH") + " FROM " + literals)),"Backslash defaults must round-trip");
            String table = DdlService.formatTable(config,schema,"ALTER_RECORDS");
            if (config.getType()==DatabaseType.HSQLDB) {
                sql(c,"CREATE TABLE " + table + " (ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, VALUE INT DEFAULT 7)");
                var changed=new ColumnDefinition("VALUE","BIGINT",0,false,false,false,"9");
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",changed);
                var actual=MetadataService.getInstance().getColumns(c,config,schema,"ALTER_RECORDS").stream().filter(col -> col.getName().equals("VALUE")).findFirst().orElseThrow();
                check(actual.getDataType()==Types.BIGINT && !actual.isNullable() && "9".equals(actual.getDefaultValue()),"HSQLDB modify must apply type, default and nullability");
                var identity=new ColumnDefinition("ID","BIGINT",0,false,true,true,"");
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",identity);
                check(MetadataService.getInstance().getColumns(c,config,schema,"ALTER_RECORDS").stream().filter(col -> col.getName().equals("ID")).findFirst().orElseThrow().isAutoIncrement(),"HSQLDB modification must preserve identity generator");
                sql(c,"INSERT INTO " + table + " DEFAULT VALUES");
                check(((Number)scalar(c,"SELECT VALUE FROM " + table)).longValue()==9,"Modified default must apply to inserts");
                var nullable=new ColumnDefinition("VALUE","BIGINT",0,true,false,false,"");
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",nullable);
                var removed=MetadataService.getInstance().getColumns(c,config,schema,"ALTER_RECORDS").stream().filter(col -> col.getName().equals("VALUE")).findFirst().orElseThrow();
                check(removed.isNullable() && removed.getDefaultValue()==null,"HSQLDB modify must drop defaults and NOT NULL");
                sql(c,"INSERT INTO " + table + " (VALUE) VALUES (NULL)");
                try { ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",changed); throw new AssertionError("NOT NULL accepted existing NULL"); } catch(SQLException expected) { check(true,"Invalid modify rejected"); }
                var unchanged=MetadataService.getInstance().getColumns(c,config,schema,"ALTER_RECORDS").stream().filter(col -> col.getName().equals("VALUE")).findFirst().orElseThrow();
                check(unchanged.isNullable() && unchanged.getDefaultValue()==null,"Failed HSQLDB modification must preserve the original definition");
            }
            if (config.getType()==DatabaseType.MYSQL) {
                sql(c,"CREATE TABLE " + table + " (ID INT AUTO_INCREMENT PRIMARY KEY, AMOUNT DECIMAL(12,2), KIND ENUM('a','b') DEFAULT 'a', MODIFIED TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, LABEL VARCHAR(100) COLLATE utf8mb4_unicode_ci COMMENT 'keep me')");
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("ID","BIGINT",0,false,false,false,""));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("AMOUNT","DECIMAL",12,true,false,false,""));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("KIND","ENUM",1,true,false,false,"a"));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("MODIFIED","TIMESTAMP",0,true,false,false,"CURRENT_TIMESTAMP"));
                ddl.alterTableModifyColumn(c,config,schema,"ALTER_RECORDS",new ColumnDefinition("LABEL","VARCHAR",200,false,false,false,""));
                check(!MetadataService.getInstance().getColumns(c,config,schema,"ALTER_RECORDS").stream().filter(col -> col.getName().equals("LABEL")).findFirst().orElseThrow().isNullable(),"Removing nullability must remove implicit DEFAULT NULL");
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
    static void credentials() {
        var secrets = new HashMap<String, com.segfault03.ideadb.state.DatabaseSettingsState.Secret>();
        var vault = new com.segfault03.ideadb.state.DatabaseSettingsState.CredentialStore() {
            public com.segfault03.ideadb.state.DatabaseSettingsState.Secret get(String id) { return secrets.get(id); }
            public void set(String id, com.segfault03.ideadb.state.DatabaseSettingsState.Secret secret) { if (secret==null) secrets.remove(id); else secrets.put(id,secret); }
        };
        var events = new java.util.concurrent.atomic.AtomicInteger();
        var settings = new com.segfault03.ideadb.state.DatabaseSettingsState(vault,events::incrementAndGet);
        var config = new ConnectionConfig(DatabaseType.MYSQL,"credentials"); config.setPassword("password-sentinel"); config.setCustomUrl("jdbc:mysql://localhost/shop_db?password=url-secret");
        settings.addConnection(config);
        String xml = com.intellij.openapi.util.JDOMUtil.writeElement(com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState()));
        check(!xml.contains("password-sentinel") && !xml.contains("url-secret"),"Settings XML must not contain passwords or JDBC URL secrets");
        var reloaded = new com.segfault03.ideadb.state.DatabaseSettingsState(vault,events::incrementAndGet); reloaded.loadState(settings.getState());
        check(reloaded.getConnection(config.getId()).getPassword().equals("password-sentinel") && reloaded.getConnection(config.getId()).getCustomUrl().equals(config.getCustomUrl()),"Secure credentials must hydrate on reload");
        reloaded.getConnection(config.getId()).setPassword("external change");
        check(reloaded.getConnection(config.getId()).getPassword().equals("password-sentinel"),"Settings must not leak mutable configuration references");
        var legacy = new com.segfault03.ideadb.state.DatabaseSettingsState.State(); legacy.connections.add(config.copy());
        reloaded.loadState(legacy);
        check(reloaded.getState().connections.get(0).getPassword().isEmpty() && secrets.containsKey(config.getId()),"Legacy plaintext credentials must migrate before XML is saved");
        config.setPassword("updated"); reloaded.updateConnection(config);
        check(secrets.get(config.getId()).password().equals("updated"),"Credential changes must replace the secure entry");
        reloaded.removeConnection(config.getId());
        check(secrets.isEmpty() && reloaded.getConnection(config.getId())==null,"Removing a connection must remove its secure entry");
        check(events.get()==5,"Add, load, migration, update and remove must publish settings changes");
        var failingVault = new com.segfault03.ideadb.state.DatabaseSettingsState.CredentialStore() {
            public com.segfault03.ideadb.state.DatabaseSettingsState.Secret get(String id) { return null; }
            public void set(String id,com.segfault03.ideadb.state.DatabaseSettingsState.Secret secret) { throw new IllegalStateException("vault unavailable"); }
        };
        var failingSettings = new com.segfault03.ideadb.state.DatabaseSettingsState(failingVault,events::incrementAndGet);
        try { failingSettings.addConnection(config); throw new AssertionError("Expected secure-storage failure"); }
        catch (IllegalStateException expected) { check(events.get()==5 && failingSettings.getConnections().isEmpty(),"Failed credential writes must not publish or change settings"); }
    }
    static void drafts() {
        var original = new ArrayList<Object>(Arrays.asList(1, new java.math.BigDecimal("12.30"), new byte[]{0, -1}, null, Timestamp.valueOf("2026-10-04 10:20:30.123456")));
        var edited = new ArrayList<Object>(original); edited.set(0, 2);
        var added = new ArrayList<Object>(original); added.set(0, 3);
        var draft = com.segfault03.ideadb.state.TableDraftState.Draft.capture(List.of("ID","AMOUNT","BYTES","NULL","TIME"), Collections.nCopies(5,"type"), List.of(original), List.of(edited,added));
        var state = new com.segfault03.ideadb.state.TableDraftState.State(); state.drafts.put("test",draft);
        var xml = com.intellij.util.xmlb.XmlSerializer.serialize(state);
        var restored = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, com.segfault03.ideadb.state.TableDraftState.State.class).drafts.get("test");
        check(restored.originalValues().get(0).get(0).equals(1) && restored.values().get(0).get(0).equals(2), "Draft must keep original row identity apart from edited keys");
        check(restored.values().size()==2 && restored.hasChanges(), "Draft must preserve newly inserted rows");
        for (int col=1;col<5;col++) check(Objects.deepEquals(original.get(col),restored.values().get(0).get(col)),"Typed draft cell must survive XML: " + col);
        original.set(0,99); ((byte[])original.get(2))[0]=7;
        check(restored.originalValues().get(0).get(0).equals(1) && ((byte[])restored.values().get(0).get(2))[0]==0,"Draft captures independent values");
        var remaining = restored.withoutRows(List.of(0));
        check(remaining.originals.isEmpty() && remaining.values().get(0).get(0).equals(3),"Delete reconciliation must preserve unsaved inserts");
        check(!restored.withoutRows(List.of(0,1)).hasChanges(),"Deleting all pending rows must clear draft");
        var store = new com.segfault03.ideadb.state.TableDraftState(); store.put("test", restored); store.remove("test");
        check(store.get("test")==null,"Successful commit clears recovery draft");
        store.put("old",restored); check(store.move("old","renamed") && store.get("old")==null && store.get("renamed")==restored,"Rename must migrate pending drafts without discarding edits");
        store.put("conflict",draft); check(!store.move("renamed","conflict") && store.get("renamed")==restored,"Rename draft conflicts must preserve both drafts");
    }
    public static void main(String[] args) throws Exception {
        assertions.set(0);
        try {
            String historySql="SELECT 'text  with   spaces', ID FROM SOME_TABLE WHERE ID IN (1,2,3,4,5);\nSELECT 2;";
            var entry=new QueryHistoryEntry(historySql);
            check(entry.toString().length()<=53 && entry.sql().equals(historySql),"History preview must preserve full executable SQL and literal whitespace");
            var readonly=new ReadOnlyResultModel(); readonly.setDataVector(new Object[][]{{1,"value"}},new Object[]{"ID","VALUE"});
            check(!readonly.isCellEditable(0,0) && !readonly.isCellEditable(0,1),"Arbitrary console results must be read-only");
            drafts();
            credentials();
            ConnectionConfig mysql = new ConnectionConfig(DatabaseType.MYSQL, "regression MySQL"); mysql.setDatabaseName("shop_db");
            mysql.setPort(Integer.getInteger("lattice.test.mysql.port", 3306));
            runEngine(mysql);
            var hsql = new ConnectionConfig(DatabaseType.HSQLDB, "regression HSQLDB");
            hsql.setPort(Integer.getInteger("lattice.test.hsqldb.port", 9001));
            runEngine(hsql);
            System.out.println("PASS functional regression assertions: " + assertions);
        } finally { manager.closeAll(); }
    }
}
