package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.DatabaseCompatibilityTest;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import java.nio.file.*;
import java.util.*;

/** Each selected artifact runs in Java 8, then exercises production services in the plugin JVM. */
public final class PostgresCompatibilityTest {
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[1]); String server=args[2];
        List<String> versions=List.of("42.2.27",server.startsWith("8.") || server.startsWith("9.0.") ? "42.7.3" : "42.7.14");
        List<Path> jars=new ArrayList<>(); List<Throwable> failures=new ArrayList<>();
        try {
            for(String version:versions) {
                try {
                    Path jar=DriverCatalog.download(DatabaseType.POSTGRESQL,version); jars.add(jar);
                    var config=new ConnectionConfig(DatabaseType.POSTGRESQL,"PostgreSQL matrix");
                    config.setPort(port); config.setPassword("lattice-test"); config.setDriverSource(DriverSource.DOWNLOAD); config.setDriverVersion(version);
                    DatabaseCompatibilityTest.java8Probe(Path.of(args[3]),Path.of(args[4]),jar,"POSTGRESQL",config.buildJdbcUrl());
                    try(var connection=DatabaseConnectionManager.getInstance().openConnection(config)) {
                        if(!connection.getMetaData().getDatabaseProductVersion().startsWith(server))
                            throw new AssertionError("Fixture server version differs from " + server);
                    }
                    DatabaseCompatibilityTest.flow(config,version,jar);
                    PostgresFeatureRegressionTest.catalogFlow(config);
                    PostgresFeatureRegressionTest.valuesFlow(config);
                    PostgresFeatureRegressionTest.sessionFlow(config);
                    System.out.println("PASS PostgreSQL full feature matrix server="+server+" driver="+version);
                } catch(Throwable failure) { failure.printStackTrace(); failures.add(failure); }
            }
        } finally {
            DatabaseConnectionManager.getInstance().closeAll(); DriverRegistry.getInstance().dispose();
            if(!Boolean.getBoolean("lattice.test.keepDependencies")) for(Path jar:jars) Files.deleteIfExists(jar);
        }
        if(!failures.isEmpty()) throw new AssertionError(failures.size()+" PostgreSQL compatibility cases failed",failures.getFirst());
    }
}
