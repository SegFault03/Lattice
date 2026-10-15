package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.DatabaseCompatibilityTest;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Selected versions run as Java 8 servers and isolated plugin drivers, never global DriverManager lookups. */
public final class H2CompatibilityTest {
    public static final List<String> VERSIONS = List.of("2.1.214", "2.2.224", "2.3.232");
    public static void main(String[] args) throws Exception {
        Path java8=Path.of(args[1]), probe=Path.of(args[2]);
        Path java21=Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path output=Path.of(System.getProperty("lattice.test.output","build/compatibility/h2")); Files.createDirectories(output);
        List<String> evidence=new ArrayList<>(); List<Throwable> failures=new ArrayList<>();
        Map<String,Path> jars=new LinkedHashMap<>();
        try {
            for(String version:VERSIONS) jars.put(version,DriverCatalog.download(DatabaseType.H2,version));
            for(var selected:jars.entrySet()) {
                var config=config(selected.getKey());
                Path runtime=selected.getKey().startsWith("2.3.") ? java21 : java8;
                if(runtime.equals(java21)) {
                    var rejected=new ProcessBuilder(java8.toString(),"-cp",probe+java.io.File.pathSeparator+selected.getValue(),"com.segfault03.ideadb.Java8DriverProbe","H2","jdbc:h2:mem:unsupported").redirectErrorStream(true).start();
                    String reason=new String(rejected.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    if(!rejected.waitFor(30,TimeUnit.SECONDS) || rejected.exitValue()==0 || !reason.contains("UnsupportedClassVersionError")) throw new AssertionError("Expected Java 8 rejection for "+selected.getKey());
                    passed(evidence,"expected Java8 rejection driver="+selected.getKey()+" (requires Java11+)");
                }
                for(HsqlMode mode:List.of(HsqlMode.MEM,HsqlMode.FILE)) {
                    config.setHsqlMode(mode); config.setDatabaseName(mode==HsqlMode.FILE ? output.resolve("file-"+selected.getKey()).toAbsolutePath().toString() : "matrix_"+UUID.randomUUID());
                    try {
                        DatabaseCompatibilityTest.java8Probe(runtime,probe,selected.getValue(),"H2",config.buildJdbcUrl());
                        DatabaseCompatibilityTest.flow(config,selected.getKey(),selected.getValue());
                        ProviderEndToEndTest.workflow(config); H2RegressionTest.regressionFlow(config);
                        passed(evidence,"embedded "+mode+" driver="+selected.getKey()+" "+(runtime.equals(java8) ? "Java8+Java21" : "Java21"));
                    } catch(Throwable failure) { failure.printStackTrace(); failures.add(failure); }
                }
                for(String mode:List.of("MySQL","PostgreSQL")) {
                    config.setCustomUrl("jdbc:h2:mem:mode_"+UUID.randomUUID()+";MODE="+mode);
                    try { ProviderEndToEndTest.workflow(config); H2RegressionTest.regressionFlow(config); passed(evidence,"MODE="+mode+" driver="+selected.getKey()); }
                    catch(Throwable failure) { failure.printStackTrace(); failures.add(failure); }
                }
            }
            for(var server:jars.entrySet()) {
                int port; try(var socket=new ServerSocket(0,50,InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
                // The fixture server is loopback only and owns an isolated base directory.
                Path base=output.resolve("server-"+server.getKey()).toAbsolutePath(); Files.createDirectories(base);
                Path serverRuntime=server.getKey().startsWith("2.3.") ? java21 : java8;
                Process process=new ProcessBuilder(serverRuntime.toString(),"-cp",server.getValue().toString(),"org.h2.tools.Server","-tcp","-tcpPort",String.valueOf(port),"-baseDir",base.toString(),"-ifNotExists")
                        .redirectErrorStream(true).redirectOutput(output.resolve("server-"+server.getKey()+".log").toFile()).start();
                try {
                    boolean ready=false;
                    for(int i=0;i<100 && process.isAlive();i++) {
                        try(var socket=new Socket("127.0.0.1",port)) { ready=true; break; }
                        catch(java.io.IOException pending) { Thread.sleep(100); }
                    }
                    if(!ready) throw new AssertionError("H2 server failed to start "+server.getKey());
                    for(var driver:jars.entrySet()) {
                        var config=config(driver.getKey()); config.setHsqlMode(HsqlMode.SERVER); config.setHost("127.0.0.1"); config.setPort(port); config.setDatabaseName("case_"+driver.getKey());
                        try {
                            DatabaseCompatibilityTest.java8Probe(driver.getKey().startsWith("2.3.") ? java21 : java8,probe,driver.getValue(),"H2",config.buildJdbcUrl());
                            try(var manager=new Owner(); var conn=manager.manager.openConnection(config)) {
                                if(!conn.getMetaData().getDatabaseProductVersion().startsWith(server.getKey())) throw new AssertionError("Unexpected server version");
                            }
                            DatabaseCompatibilityTest.flow(config,driver.getKey(),driver.getValue());
                            ProviderEndToEndTest.workflow(config); H2RegressionTest.regressionFlow(config);
                            passed(evidence,"TCP server="+server.getKey()+" driver="+driver.getKey()+" driverRuntime="+(driver.getKey().startsWith("2.3.") ? "Java21" : "Java8+Java21")+" serverRuntime="+(serverRuntime.equals(java8) ? "Java8" : "Java21"));
                        } catch(Throwable failure) { failure.printStackTrace(); failures.add(failure); }
                    }
                } finally { process.destroy(); if(!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } }
            }
        } catch(Throwable failure) {
            failure.printStackTrace(); failures.add(failure);
        } finally {
            DatabaseConnectionManager.getInstance().closeAll(); DriverRegistry.getInstance().dispose();
            evidence.add("Failures: "+failures.size()); Files.write(output.resolve("results.txt"),evidence);
        }
        if(!failures.isEmpty()) throw new AssertionError(failures.size()+" H2 compatibility cases failed",failures.getFirst());
    }
    private static ConnectionConfig config(String version) {
        var config=new ConnectionConfig(DatabaseType.H2,"H2 matrix"); config.setDriverSource(DriverSource.DOWNLOAD); config.setDriverVersion(version); return config;
    }
    private static void passed(List<String> evidence,String message) { evidence.add("PASS "+message); System.out.println("PASS "+message); }
    private static final class Owner implements AutoCloseable {
        final DatabaseConnectionManager manager=new DatabaseConnectionManager(); public void close() { manager.dispose(); }
    }
}
