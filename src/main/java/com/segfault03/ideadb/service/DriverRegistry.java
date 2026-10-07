package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DriverSource;
import java.nio.file.Path;
import java.nio.file.Files;

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
    private final Map<String, Driver> driverCache = new ConcurrentHashMap<>();
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
        Driver cached = driverCache.get("bundled:" + type.name());
        if (cached != null) {
            return cached;
        }

        // Try standard classloader first
        try {
            Class<?> clazz = Class.forName(type.getDriverClassName());
            Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
            driverCache.put("bundled:" + type.name(), driver);
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

        Driver driver = loadIsolated(type, driverJar.toPath());
        driverCache.put("bundled:" + type.name(), driver);
        return driver;
    }

    /** The packaged driver JAR shipped beside the plugin classes, or null when it cannot be located. */
    public synchronized File packagedJar(DatabaseType type) {
        return findJarFile(type == DatabaseType.MYSQL ? "mysql-connector" : "hsqldb");
    }

    /** An explicit selection must never resolve to the bundled driver's classes. */
    public synchronized Driver getDriver(ConnectionConfig config) throws Exception {
        if (disposed) throw new IllegalStateException("Driver registry is disposed");
        if (config.getDriverSource() == DriverSource.BUNDLED) {
            if (config.getDriverVersion().isBlank()) return getDriver(config.getType());
            // A named bundled driver is a retained download; never silently fall back to the packaged one.
            var retained = retainedJar(config.getType(), config.getDriverVersion());
            if (retained == null) throw new IllegalStateException("The downloaded driver is no longer stored. Download " + config.getDriverVersion() + " again.");
            return loadCached(config.getType(), retained);
        }
        Path path = config.getDriverSource() == DriverSource.DOWNLOAD
                ? DriverCatalog.downloadedJar(config.getType(), config.getDriverVersion()) : Path.of(config.getDriverJarPath());
        return loadCached(config.getType(), path);
    }

    /** The retained JAR backing a bundled selection, or null when the packaged driver should be used. */
    public static Path retainedJar(DatabaseType type, String version) {
        String selection = version == null ? "" : version.trim();
        return selection.isEmpty() || !DriverCatalog.isInstalled(type, selection)
                ? null : DriverCatalog.downloadedJar(type, selection);
    }

    /** Keyed by path, size and modification time, so replacing a JAR on disk never serves a stale driver. */
    private Driver loadCached(DatabaseType type, Path jar) throws Exception {
        if (!Files.isRegularFile(jar)) throw new IllegalStateException("Driver JAR is missing. Download the selected version or choose an existing local JAR.");
        Path path = jar.toRealPath();
        String key = type + ":" + path + ":" + Files.size(path) + ":" + Files.getLastModifiedTime(path);
        Driver cached = driverCache.get(key);
        if (cached != null) return cached;
        Driver driver = loadIsolated(type, path);
        driverCache.put(key, driver);
        return driver;
    }

    private Driver loadIsolated(DatabaseType type, Path jar) throws Exception {
        String driverClass = DriverCatalog.validateJar(type, jar);
        URLClassLoader classLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, DriverRegistry.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(JdbcDriverCleanup.class.getName())) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var resource = DriverRegistry.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                            if (resource == null) throw new ClassNotFoundException(name);
                            byte[] bytes = resource.readAllBytes(); loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                // JDBC namespaces are child-first, including resources and static driver registration.
                // Java/IDE/plugin classes retain the parent loader so java.sql.Driver is shared.
                if (name.startsWith("com.mysql.") || name.startsWith("org.hsqldb.")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
            @Override public URL getResource(String name) {
                if (name.startsWith("com/mysql/") || name.startsWith("org/hsqldb/")) {
                    URL own = findResource(name); if (own != null) return own;
                }
                return super.getResource(name);
            }
        };
        try {
            Driver driver = (Driver)Class.forName(driverClass, true, classLoader).getDeclaredConstructor().newInstance();
            ownedLoaders.add(classLoader); return driver;
        } catch (Exception | Error failure) {
            try { Class.forName(JdbcDriverCleanup.class.getName(), true, classLoader).getMethod("release", ClassLoader.class).invoke(null, classLoader); }
            catch (ReflectiveOperationException cleanup) { failure.addSuppressed(cleanup); }
            classLoader.close(); throw failure;
        }
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

    /** Sorted so the same packaged version is chosen on every start. */
    private File findJarFile(String keyword) {
        for (File dir : searchDirectories) {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT)) && name.endsWith(".jar"));
                if (files != null && files.length > 0) {
                    java.util.Arrays.sort(files);
                    return files[0];
                }
            }
        }
        return null;
    }
}
