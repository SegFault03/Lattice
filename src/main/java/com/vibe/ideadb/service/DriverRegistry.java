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

public class DriverRegistry implements com.intellij.openapi.Disposable {
    private static final class Standalone { private static final DriverRegistry INSTANCE=new DriverRegistry(); }
    private final List<URLClassLoader> ownedLoaders=new ArrayList<>();
    private boolean disposed;
    private final Map<DatabaseType, Driver> driverCache = new ConcurrentHashMap<>();
    private final List<File> searchDirectories = new ArrayList<>();

    public DriverRegistry() {
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

        File localLib = new File("lib");
        if(localLib.isDirectory()) searchDirectories.add(localLib);

    }

    public static DriverRegistry getInstance() {
        var application=com.intellij.openapi.application.ApplicationManager.getApplication();
        return application==null ? Standalone.INSTANCE : application.getService(DriverRegistry.class);
    }

    public synchronized void addSearchDirectory(File dir) {
        if (dir != null && dir.isDirectory() && !searchDirectories.contains(dir)) {
            searchDirectories.add(0, dir);
        }
    }

    public synchronized Driver getDriver(DatabaseType type) throws Exception {
        if(disposed) throw new IllegalStateException("Driver registry is disposed");
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
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jarUrl}, DriverRegistry.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(!name.equals(JdbcDriverCleanup.class.getName())) return super.loadClass(name,resolve);
                Class<?> loaded=findLoadedClass(name);
                if(loaded==null) {
                    try(var resource=DriverRegistry.class.getResourceAsStream("/" + name.replace('.','/') + ".class")) {
                        if(resource==null) throw new ClassNotFoundException(name);
                        byte[] bytes=resource.readAllBytes(); loaded=defineClass(name,bytes,0,bytes.length);
                    } catch(java.io.IOException error) { throw new ClassNotFoundException(name,error); }
                }
                if(resolve) resolveClass(loaded); return loaded;
            }
        };
        try {
            Class<?> clazz=Class.forName(type.getDriverClassName(),true,classLoader);
            Driver driver=(Driver)clazz.getDeclaredConstructor().newInstance();
            ownedLoaders.add(classLoader); driverCache.put(type,driver); return driver;
        } catch(Exception | Error failure) { classLoader.close(); throw failure; }
    }

    @Override public synchronized void dispose() {
        disposed=true;
        JdbcDriverCleanup.release(DriverRegistry.class.getClassLoader());
        for(URLClassLoader loader:ownedLoaders) {
            try { Class.forName(JdbcDriverCleanup.class.getName(),true,loader).getMethod("release",ClassLoader.class).invoke(null,loader); }
            catch(ReflectiveOperationException ignored) { }
            try { loader.close(); } catch(java.io.IOException ignored) { }
        }
        ownedLoaders.clear(); driverCache.clear(); searchDirectories.clear();
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
