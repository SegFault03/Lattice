package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.InstalledDriver;
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
        File jar = DriverRegistry.getInstance().packagedJar(type);
        return jar == null ? "" : DriverCatalog.versionOf(type, jar.getName());
    }

    /** True when a selection names a retained download rather than the packaged driver. */
    public static boolean isRetained(DatabaseType type, String version) {
        return DriverRegistry.retainedJar(type, version) != null;
    }
}