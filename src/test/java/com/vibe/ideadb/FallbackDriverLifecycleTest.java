package com.vibe.ideadb;

import com.vibe.ideadb.model.*;
import com.vibe.ideadb.service.*;
import java.util.*;

/** Run without JDBC jars on the parent classpath to exercise fallback loading and deregistration. */
public final class FallbackDriverLifecycleTest {
    public static void main(String[] args) throws Exception {
        var registry=new DriverRegistry();
        var cleanup=new ArrayList<Class<?>>();
        var loaders=new ArrayList<ClassLoader>();
        try {
            for(DatabaseType type:DatabaseType.values()) {
                var driver=registry.getDriver(type); ClassLoader owner=driver.getClass().getClassLoader();
                if(owner==DriverRegistry.class.getClassLoader()) throw new AssertionError("Fallback classloader was not exercised");
                var properties=new Properties(); properties.setProperty("user",type==DatabaseType.MYSQL ? "root" : "SA"); properties.setProperty("password","");
                try(var connection=driver.connect(type==DatabaseType.MYSQL ? "jdbc:mysql://localhost:3306/shop_db" : "jdbc:hsqldb:hsql://localhost:9001/testdb",properties)) { if(connection==null || connection.isClosed()) throw new AssertionError("Fallback connection failed"); }
                cleanup.add(Class.forName(JdbcDriverCleanup.class.getName(),true,owner)); loaders.add(owner);
            }
        } finally { registry.dispose(); }
        for(int i=0;i<cleanup.size();i++) if(((Number)cleanup.get(i).getMethod("registeredCount",ClassLoader.class).invoke(null,loaders.get(i))).intValue()!=0) throw new AssertionError("Fallback driver registrations retained");
        try { registry.getDriver(DatabaseType.MYSQL); throw new AssertionError("Disposed registry accepted work"); } catch(IllegalStateException expected) { }
        System.out.println("PASS fallback driver connections, deregistration and disposal");
    }
}
