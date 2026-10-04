package com.segfault03.ideadb;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in downloadable driver/server matrix; uses only dedicated test fixtures and temporary schemas. */
public final class DatabaseCompatibilityTest {
    private static final List<String> MYSQL = List.of("5.1.49", "6.0.6", "8.0.33", "8.4.0", "9.0.0");
    private static final List<String> HSQL = List.of("2.2.9", "2.3.0", "2.3.6", "2.4.1", "2.5.0", "2.5.2", "2.6.1-jdk8", "2.7.0-jdk8", "2.7.3-jdk8", "2.7.4-jdk8");
    private static final DatabaseConnectionManager MANAGER = DatabaseConnectionManager.getInstance();
    private static final DataService DATA = DataService.getInstance();
    private static final DdlService DDL = DdlService.getInstance();
    private static void require(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
    private static Object scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) { require(result.next(), "Missing scalar"); return result.getObject(1); }
    }
    private static void execute(Connection connection, String sql) throws Exception { try (Statement statement = connection.createStatement()) { statement.execute(sql); } }
    private static void flow(ConnectionConfig config, String version) throws Exception {
        var test = MANAGER.testConnection(config);
        require(test.isSuccess(), test.getSummaryMessage());
        require(test.getDriverVersion() != null && !test.getDriverVersion().isBlank(), "No reported driver version");
        require(test.getDatabaseProductVersion() != null && !test.getDatabaseProductVersion().isBlank(), "No detected server version");
        var driver = DriverRegistry.getInstance().getDriver(config);
        require(Path.of(driver.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(DriverCatalog.downloadedJar(config.getType(),version).toRealPath()),"Selected artifact was overridden");
        require(driver.getClass().getClassLoader() != DriverRegistry.class.getClassLoader(), "Explicit driver must be isolated from bundled driver");
        var local = config.copy(); local.setDriverSource(DriverSource.LOCAL_JAR); local.setDriverJarPath(DriverCatalog.downloadedJar(config.getType(), version).toString());
        require(DriverRegistry.getInstance().getDriver(local) == driver, "Local JAR selection should load the same selected artifact");
        String schema = "compat_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = MANAGER.openConnection(config)) {
            DDL.createDatabase(c, config, schema);
            try {
                ColumnDefinition id = new ColumnDefinition("id", "INTEGER", 0, true, false, false, ""); id.setPrimaryKey(true); id.setNullable(false);
                ColumnDefinition text = new ColumnDefinition("name", "VARCHAR", 0, true, false, false, ""); text.setSize(80); text.setDefaultValue("TEXT:c:\\old's");
                ColumnDefinition time = new ColumnDefinition("created", "TIMESTAMP", 0, true, false, false, ""); time.setNullable(false); time.setDefaultValue("CURRENT_TIMESTAMP");
                DDL.createTable(c, config, schema, "records", List.of(id, text, time));
                String table = DdlService.formatTable(config, schema, "records");
                DATA.commitChanges(c, config, schema, "records", List.of(Map.of("id",1), Map.of("id",2,"name","value")), List.of());
                require(DATA.countRows(c,config,schema,"records",null)==2,"Insert/count failed");
                require("c:\\old's".equals(scalar(c,"SELECT " + DdlService.quoteIdentifier(config,"name") + " FROM " + table + " WHERE " + DdlService.quoteIdentifier(config,"id") + "=1")),"Backslash/quote default changed");
                require(MetadataService.getInstance().getColumns(c,config,schema,"records").size()==3,"Temporal metadata failed");
                var page=DATA.fetchData(c,config,schema,"records",null,null,1,0); require(!page.hasError() && ((Number)page.getRows().get(0).get(0)).intValue()==1,"Ordered fetch failed: " + page.getError() + " rows=" + page.getRows());
                DATA.commitChanges(c,config,schema,"records",List.of(),List.of(new DataService.RowUpdate(Map.of("id",3,"name","updated"),Map.of("id",1))));
                try { DATA.commitChanges(c,config,schema,"records",List.of(Map.of("id",4),Map.of("id",4)),List.of()); throw new AssertionError("Expected rollback"); }
                catch(SQLException duplicate) { require(DATA.countRows(c,config,schema,"records",null)==2,"Failed insert was partially committed"); }
                DDL.alterTableRenameColumn(c,config,schema,"records","name","renamed");
                var renamed = MetadataService.getInstance().getColumns(c,config,schema,"records").stream().filter(column -> column.getName().equals("renamed")).findFirst().orElseThrow();
                require(renamed.getColumnSize()==80 && renamed.getDefaultValue()!=null,"Rename lost column definition");
                ColumnDefinition modified = new ColumnDefinition("renamed", "VARCHAR", 0, true, false, false, ""); modified.setSize(90); modified.setDefaultValue("TEXT:new\\value");
                DDL.alterTableModifyColumn(c,config,schema,"records",modified);
                DATA.commitChanges(c,config,schema,"records",List.of(Map.of("id",5)),List.of());
                require("new\\value".equals(scalar(c,"SELECT " + DdlService.quoteIdentifier(config,"renamed") + " FROM " + table + " WHERE " + DdlService.quoteIdentifier(config,"id") + "=5")),"Modified default changed");
                var rows = DATA.fetchData(c,config,schema,"records",null,null,10,0);
                Path output=Files.createTempFile("lattice-compat", ".sql");
                String export;
                try { ExportService.getInstance().exportToSqlInsert(config,schema,"records",rows,output.toFile()); export=Files.readString(output); }
                finally { Files.deleteIfExists(output); }
                DDL.createTable(c,config,schema,"copy",List.of(id,modified,time));
                for(String line:export.replace(table,DdlService.formatTable(config,schema,"copy")).split("\\n")) if(line.startsWith("INSERT INTO")) execute(c,line);
                require(DATA.countRows(c,config,schema,"copy",null)==3,"SQL export reimport failed");
                var copied=DATA.fetchData(c,config,schema,"copy",null,null,10,0);
                require(rows.getRows().equals(copied.getRows()),"SQL export changed values: original="+rows.getRows()+" imported="+copied.getRows());
                String ddl=DDL.getCreateTableStatement(c,config,schema,new TableMetadata(schema,null,"records","TABLE"));
                require(ddl.contains("CREATE") && !ddl.contains("Partial reconstruction"),"Native CREATE export failed: " + ddl);
                DATA.deleteRows(c,config,schema,"records",List.of(Map.of("id",2),Map.of("id",3)));
                require(DATA.countRows(c,config,schema,"records",null)==1,"Batch delete failed");
                DDL.dropTable(c,config,schema,"copy"); DDL.dropTable(c,config,schema,"records");
            } finally { DDL.dropDatabase(c,config,schema); }
        }
        System.out.println("PASS matrix " + test.getDatabaseProductName() + " " + test.getDatabaseProductVersion() + " / driver " + version);
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("download")) {
            for(DatabaseType type:DatabaseType.values()) {
                require(DriverCatalog.availableVersions(type).size()>10,"Incomplete version catalogue");
                for(String version:type==DatabaseType.MYSQL ? MYSQL : HSQL) System.out.println("Downloaded " + version + " " + DriverCatalog.download(type,version));
            }
            return;
        }
        List<Throwable> failures=new ArrayList<>();
        if(args[0].equals("mysql")) {
            int port=Integer.parseInt(args[1]); String server=args[2];
            for(String version:server.startsWith("8.") ? List.of("8.0.33","8.4.0","9.0.0") : List.of("5.1.49","6.0.6","8.0.33")) {
                try {
                    var config=new ConnectionConfig(DatabaseType.MYSQL,"compatibility"); config.setPort(port); config.setDriverSource(DriverSource.DOWNLOAD); config.setDriverVersion(version); flow(config,version);
                } catch(Throwable failure) { failure.printStackTrace(); failures.add(failure); }
            }
        } else {
            Path java8=Path.of(args[1]), tests=Path.of(args[2]); int port=19020;
            for(String version:args.length > 3 ? List.of(args[3]) : HSQL) {
                Path jar=DriverCatalog.downloadedJar(DatabaseType.HSQLDB,version);
                int selectedPort=port++;
                Path logs = Path.of(System.getProperty("lattice.test.output", "build/compatibility"));
                Files.createDirectories(logs);
                Process server=new ProcessBuilder(java8.toString(),"-cp",jar.toString(),"org.hsqldb.server.Server","--address","127.0.0.1","--database.0","mem:compat","--dbname.0","compat","--port",String.valueOf(selectedPort),"--silent","true")
                        .redirectErrorStream(true).redirectOutput(logs.resolve("server-"+version+".log").toFile()).start();
                try {
                    boolean ready=false;
                    for(int attempt=0;attempt<80;attempt++) {
                        if(!server.isAlive()) break;
                        try(var socket=new java.net.Socket("127.0.0.1",selectedPort)) { ready=true; break; }
                        catch(java.io.IOException pending) { Thread.sleep(100); }
                    }
                    require(ready,"Java 8 HSQLDB server did not start: " + version);
                    Process probe=new ProcessBuilder(java8.toString(),"-cp",tests+java.io.File.pathSeparator+jar,"com.segfault03.ideadb.Java8DriverProbe","HSQLDB","jdbc:hsqldb:hsql://localhost:"+selectedPort+"/compat").inheritIO().start();
                    require(probe.waitFor(30,TimeUnit.SECONDS) && probe.exitValue()==0,"Java 8 driver failed");
                    var config=new ConnectionConfig(DatabaseType.HSQLDB,"compatibility"); config.setPort(selectedPort); config.setDatabaseName("compat"); config.setDriverSource(DriverSource.DOWNLOAD); config.setDriverVersion(version); flow(config,version);
                    try(Connection c=MANAGER.openConnection(config)) {
                        try { execute(c,"SHUTDOWN"); }
                        catch(SQLException shutdownDisconnect) { if(!server.waitFor(5,TimeUnit.SECONDS)) throw shutdownDisconnect; }
                    }
                } catch(Throwable failure) { failure.printStackTrace(); failures.add(failure); }
                finally { if(!server.waitFor(5,TimeUnit.SECONDS)) { server.destroy(); server.waitFor(5,TimeUnit.SECONDS); } }
            }
        }
        MANAGER.closeAll(); DriverRegistry.getInstance().dispose();
        if(!failures.isEmpty()) throw new AssertionError(failures.size()+" compatibility cases failed",failures.get(0));
    }
}
