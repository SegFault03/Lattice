package com.segfault03.ideadb.service;

import java.sql.*;

/** Loaded alongside a fallback driver so DriverManager permits access to that loader's registrations. */
public final class JdbcDriverCleanup {
    private JdbcDriverCleanup() {}
    public static int registeredCount(ClassLoader owner) {
        int count=0; var drivers=DriverManager.getDrivers();
        while(drivers.hasMoreElements()) if(drivers.nextElement().getClass().getClassLoader()==owner) count++;
        return count;
    }
    public static void release(ClassLoader owner) {
        var drivers=DriverManager.getDrivers();
        while(drivers.hasMoreElements()) {
            Driver driver=drivers.nextElement();
            if(driver.getClass().getClassLoader()==owner && (driver.getClass().getName().startsWith("com.mysql.") || driver.getClass().getName().startsWith("org.hsqldb."))) {
                try { DriverManager.deregisterDriver(driver); } catch(SQLException ignored) { }
            }
        }
        for (String name : new String[]{"com.mysql.cj.jdbc.AbandonedConnectionCleanupThread", "com.mysql.jdbc.AbandonedConnectionCleanupThread"}) {
            try {
                Class<?> cleanup = Class.forName(name, false, owner);
                if (cleanup.getClassLoader() == owner) {
                    try { cleanup.getMethod("uncheckedShutdown").invoke(null); }
                    catch (NoSuchMethodException legacy) { cleanup.getMethod("shutdown").invoke(null); }
                }
            } catch (ReflectiveOperationException ignored) { }
        }
    }
}
