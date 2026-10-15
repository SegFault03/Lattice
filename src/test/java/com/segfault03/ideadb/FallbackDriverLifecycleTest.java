package com.segfault03.ideadb;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import java.util.*;

/** Run without JDBC jars on the parent classpath to exercise fallback loading and deregistration. */
public final class FallbackDriverLifecycleTest {
    public static void main(String[] args) throws Exception {
        var registry=new DriverRegistry();
        if(args.length>0) registry.addSearchDirectory(new java.io.File(args[0]));
        var cleanup=new ArrayList<Class<?>>();
        var loaders=new ArrayList<ClassLoader>();
        try {
            for(DatabaseType type:DatabaseType.values()) {
                var driver=registry.getDriver(type); ClassLoader owner=driver.getClass().getClassLoader();
                if(owner==DriverRegistry.class.getClassLoader()) throw new AssertionError("Fallback classloader was not exercised");
                var properties=new Properties(); properties.setProperty("user",type.getDefaultUser()); properties.setProperty("password",type==DatabaseType.POSTGRESQL ? System.getProperty("lattice.test.postgres.password","lattice-test") : "");
                String url=type==DatabaseType.H2 ? "jdbc:h2:mem:fallback_lifecycle" : type==DatabaseType.POSTGRESQL ? "jdbc:postgresql://localhost:"+Integer.getInteger("lattice.test.postgres.port",5432)+"/postgres" : type==DatabaseType.MYSQL ? "jdbc:mysql://localhost:"+Integer.getInteger("lattice.test.mysql.port",3306)+"/shop_db" : "jdbc:hsqldb:hsql://localhost:"+Integer.getInteger("lattice.test.hsqldb.port",9001)+"/testdb";
                if(type!=DatabaseType.POSTGRESQL || Boolean.getBoolean("lattice.test.postgres.enabled")) try(var connection=driver.connect(url,properties)) {
                    if(connection==null || connection.isClosed()) throw new AssertionError("Fallback connection failed");
                    String namespace = "FALLBACK_KEYS_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
                    var config = new ConnectionConfig(type, "isolated key metadata");
                    DdlService.getInstance().createDatabase(connection, config, namespace);
                    try {
                        com.segfault03.ideadb.provider.ProviderEndToEndTest.verifyKeyMetadata(connection, config, namespace);
                    } finally { DdlService.getInstance().dropDatabase(connection, config, namespace); }
                }
                cleanup.add(Class.forName(JdbcDriverCleanup.class.getName(),true,owner)); loaders.add(owner);
            }
        } finally { registry.dispose(); }
        for(int i=0;i<cleanup.size();i++) if(((Number)cleanup.get(i).getMethod("registeredCount",ClassLoader.class).invoke(null,loaders.get(i))).intValue()!=0) throw new AssertionError("Fallback driver registrations retained");
        try { registry.getDriver(DatabaseType.MYSQL); throw new AssertionError("Disposed registry accepted work"); } catch(IllegalStateException expected) { }
        System.out.println("PASS fallback driver connections, aliased/composite key metadata, deregistration and disposal");
    }
}
