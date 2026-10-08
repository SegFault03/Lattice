package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.InstalledDriver;
import com.intellij.openapi.project.Project;
import com.intellij.ide.plugins.PluginManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drivers already available on this machine: the packaged driver, retained downloads, and Maven
 * local-repository artifacts.
 * <p>Retained JARs are never removed on startup or disposal, so a version is fetched at most once
 * and stays selectable in the available-driver list across IDE restarts.
 */
public final class DriverStore {
    private DriverStore() {}

    /** Every driver a connection can use without another download, grouped by local source. */
    public static List<InstalledDriver> installed(DatabaseType type) {
        return installed(type, null);
    }

    /** Every driver usable without another download, with the IDE project's Maven cache included. */
    public static List<InstalledDriver> installed(DatabaseType type, Project project) {
        List<InstalledDriver> drivers = new ArrayList<>();
        drivers.add(packaged(type));
        java.util.Set<String> knownVersions = new java.util.LinkedHashSet<>();
        if (!drivers.get(0).version().isBlank()) knownVersions.add(drivers.get(0).version());
        for (String version : DriverCatalog.downloadedVersions(type)) {
            drivers.add(new InstalledDriver(type, version, false, DriverCatalog.downloadedJar(type, version)));
            knownVersions.add(version);
        }
        Path localRepository = MavenRepositoryLocator.localRepository(project);
        for (String version : DriverCatalog.discoveredVersions(type, localRepository)) {
            if (knownVersions.add(version))
                drivers.add(new InstalledDriver(type, version, InstalledDriver.Kind.DISCOVERED,
                        DriverCatalog.discoveredJar(type, version, localRepository)));
        }
        return List.copyOf(drivers);
    }

    /** A validated driver declared by the current project's POM and present in its configured Maven repository. */
    public static Optional<InstalledDriver> projectDefault(DatabaseType type, Project project) {
        if (project == null || project.getBasePath() == null || project.getBasePath().isBlank()) return Optional.empty();
        Path pom = Path.of(project.getBasePath()).resolve("pom.xml");
        return MavenPomDriverResolver.find(type, project, pom, MavenRepositoryLocator.localRepository(project))
                // There is no benefit in creating a duplicate available-driver entry for the exact bundled release.
                .filter(driver -> !driver.version().equals(packagedVersion(type)));
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

    /** True for a retained download or a previously discovered Maven JAR that is still usable. */
    public static boolean isAvailable(DatabaseType type, String version, String selectedJar) {
        if (version == null || version.isBlank()) return false;
        if (isRetained(type, version)) return true;
        if (selectedJar == null || selectedJar.isBlank()) return false;
        try {
            Path jar = Path.of(selectedJar);
            return version.equals(DriverCatalog.versionOf(type, jar.getFileName().toString()))
                    && DriverCatalog.validateJar(type, jar) != null;
        } catch (Exception | LinkageError unavailable) {
            return false;
        }
    }
}
