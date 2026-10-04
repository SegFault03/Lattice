package com.vibe.ideadb.service;

import com.vibe.ideadb.model.DatabaseType;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DriverRegistry {
    private static final DriverRegistry INSTANCE = new DriverRegistry();
    private final Map<DatabaseType, Driver> driverCache = new ConcurrentHashMap<>();
    private final List<File> searchDirectories = new ArrayList<>();

    private DriverRegistry() {
        // 1. Detect directory of current running class/jar
        try {
            File codeSourceFile = new File(DriverRegistry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (codeSourceFile.isFile()) {
                File parentDir = codeSourceFile.getParentFile();
                if (parentDir != null && parentDir.isDirectory()) {
                    searchDirectories.add(parentDir);
                }
            } else if (codeSourceFile.isDirectory()) {
                searchDirectories.add(codeSourceFile);
                File libSibling = new File(codeSourceFile.getParentFile(), "lib");
                if (libSibling.exists() && libSibling.isDirectory()) {
                    searchDirectories.add(libSibling);
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Add local workspace plugin lib folder as fallback
        File localLib = new File("/path/to/workspace/Lattice/lib");
        if (localLib.exists() && localLib.isDirectory()) {
            searchDirectories.add(localLib);
        }
        File legacyLib = new File("/path/to/workspace/proto/idea-2026.2.3.win/db-navigator-plugin/lib");
        if (legacyLib.exists() && legacyLib.isDirectory()) {
            searchDirectories.add(legacyLib);
        }
    }

    public static DriverRegistry getInstance() {
        return INSTANCE;
    }

    public void addSearchDirectory(File dir) {
        if (dir != null && dir.isDirectory() && !searchDirectories.contains(dir)) {
            searchDirectories.add(0, dir);
        }
    }

    public synchronized Driver getDriver(DatabaseType type) throws Exception {
        Driver cached = driverCache.get(type);
        if (cached != null) {
            return cached;
        }

        // Try standard classloader first
        try {
            Class<?> clazz = Class.forName(type.getDriverClassName());
            Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
            driverCache.put(type, driver);
            return driver;
        } catch (ClassNotFoundException ignored) {
        }

        // Search for matching driver jar file
        String jarKeyword = type == DatabaseType.MYSQL ? "mysql-connector" : "hsqldb";
        File driverJar = findJarFile(jarKeyword);

        if (driverJar == null || !driverJar.exists()) {
            throw new IllegalStateException("JDBC Driver jar for " + type.getDisplayName() + " not found. "
                    + "Please ensure " + jarKeyword + "*.jar is in the plugin's lib directory.");
        }

        URL jarUrl = driverJar.toURI().toURL();
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl}, DriverRegistry.class.getClassLoader());
        Class<?> clazz = Class.forName(type.getDriverClassName(), true, classLoader);
        Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
        driverCache.put(type, driver);
        return driver;
    }

    private File findJarFile(String keyword) {
        for (File dir : searchDirectories) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.toLowerCase().contains(keyword.toLowerCase()) && name.endsWith(".jar"));
                if (files != null && files.length > 0) {
                    return files[0];
                }
            }
        }
        return null;
    }
}
