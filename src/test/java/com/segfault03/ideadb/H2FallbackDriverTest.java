package com.segfault03.ideadb;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.*;
import java.io.File;
import java.util.Properties;

/** A separate JVM deliberately excludes JDBC JARs from the application classpath. */
public final class H2FallbackDriverTest {
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("org.h2.Driver", false, DriverRegistry.class.getClassLoader());
            throw new AssertionError("H2 must be absent from the parent classpath");
        } catch (ClassNotFoundException expected) { }
        var registry = new DriverRegistry();
        registry.addSearchDirectory(new File(args[0]));
        ClassLoader owner;
        Class<?> cleanup;
        try {
            var driver = registry.getDriver(DatabaseType.H2);
            owner = driver.getClass().getClassLoader();
            if (owner == DriverRegistry.class.getClassLoader()) throw new AssertionError("Fallback was not isolated");
            if (registry.getDriver(DatabaseType.H2) != driver) throw new AssertionError("Packaged driver was not cached");
            var properties = new Properties(); properties.setProperty("user", "sa");
            try (var connection = driver.connect("jdbc:h2:mem:fallback_h2", properties);
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 42")) {
                if (!rows.next() || rows.getInt(1) != 42) throw new AssertionError("Fallback query failed");
                rows.close();
                var config = new ConnectionConfig(DatabaseType.H2, "Isolated H2 keys");
                DdlService.getInstance().createDatabase(connection, config, "FALLBACK_KEYS");
                try {
                    com.segfault03.ideadb.provider.ProviderEndToEndTest.verifyKeyMetadata(connection, config, "FALLBACK_KEYS");
                } finally { DdlService.getInstance().dropDatabase(connection, config, "FALLBACK_KEYS"); }
            }
            cleanup = Class.forName(JdbcDriverCleanup.class.getName(), true, owner);
        } finally { registry.dispose(); }
        if (((Number)cleanup.getMethod("registeredCount", ClassLoader.class).invoke(null, owner)).intValue() != 0)
            throw new AssertionError("Fallback driver registration leaked");
        try { registry.getDriver(DatabaseType.H2); throw new AssertionError("Disposed registry accepted work"); }
        catch (IllegalStateException expected) { }
        System.out.println("PASS H2 packaged fallback, isolated query and aliased/composite key metadata, cache, deregistration and disposal");
    }
}
