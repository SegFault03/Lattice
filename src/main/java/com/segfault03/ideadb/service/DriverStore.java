package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.InstalledDriver;
import com.intellij.ide.plugins.PluginManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Drivers that are already on this machine: the packaged driver plus every retained download.
 * <p>Retained JARs are never removed on startup or disposal, so a version is fetched at most once
 * and stays selectable in the bundled driver list across IDE restarts.
 */
public final class DriverStore {
    private DriverStore() {}

    /** Every driver a connection can use without another download, packaged first then newest. */
    public static List<InstalledDriver> installed(DatabaseType type) {
        List<InstalledDriver> drivers = new ArrayList<>();
        drivers.add(packaged(type));
        for (String version : DriverCatalog.downloadedVersions(type))
            drivers.add(new InstalledDriver(type, version, false, DriverCatalog.downloadedJar(type, version)));
        return List.copyOf(drivers);
    }

    public static InstalledDriver packaged(DatabaseType type) {
        return new InstalledDriver(type, packagedVersion(type), true);
    }

    /**
     * The packaged driver version, read from the shipped JAR name.
     * A missing or unparseable JAR yields a blank version; the packaged driver still loads.
     */
    public static String packagedVersion(DatabaseType type) {
        File jar = packagedJar(type);
        String version = jar == null ? "" : DriverCatalog.versionOf(type, jar.getName());
        if (!version.isBlank()) return version;

        // Some IDE classloaders expose a driver JAR through the JDBC class itself but not through
        // the registry's search directories. Resolve its code source as a fallback for display.
        try {
            Class<?> driver = Class.forName(type.getDriverClassName(), false, DriverStore.class.getClassLoader());
            var codeSource = driver.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                var location = codeSource.getLocation();
                if ("file".equalsIgnoreCase(location.getProtocol())) {
                    File driverJar = new File(location.toURI());
                    version = DriverCatalog.versionOf(type, driverJar.getName());
                    if (!version.isBlank()) return version;
                }
            }
            Package driverPackage = driver.getPackage();
            return driverPackage == null ? "" : java.util.Objects.toString(driverPackage.getImplementationVersion(), "");
        } catch (Exception | LinkageError unavailable) {
            return "";
        }
    }

    /**
     * Resolve shipped dependencies from the plugin's installation directory first. In a packaged
     * IDE the plugin class loader may not expose the dependency JAR through its protection domain,
     * and the registry's working-directory fallback is not reliable when the IDE starts elsewhere.
     */
    private static File packagedJar(DatabaseType type) {
        String keyword = type == DatabaseType.MYSQL ? "mysql-connector" : "hsqldb";
        try {
            var descriptor = PluginManager.getPluginByClass(DriverStore.class);
            Path pluginPath = descriptor == null ? null : descriptor.getPluginPath();
            if (pluginPath != null) {
                Path libraryDirectory = Files.isDirectory(pluginPath.resolve("lib"))
                        ? pluginPath.resolve("lib") : pluginPath;
                try (var jars = Files.newDirectoryStream(libraryDirectory, "*.jar")) {
                    List<Path> matches = new ArrayList<>();
                    for (Path candidate : jars) {
                        String name = candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (name.contains(keyword)) matches.add(candidate);
                    }
                    matches.sort(Path::compareTo);
                    if (!matches.isEmpty()) return matches.get(0).toFile();
                }
            }
        } catch (Exception | LinkageError unavailable) {
            // Fall through to DriverRegistry for development/test classpaths.
        }
        return DriverRegistry.getInstance().packagedJar(type);
    }

    /** True when a selection names a retained download rather than the packaged driver. */
    public static boolean isRetained(DatabaseType type, String version) {
        return DriverRegistry.retainedJar(type, version) != null;
    }
}
