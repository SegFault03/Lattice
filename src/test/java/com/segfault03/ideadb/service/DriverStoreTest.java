package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.DriverSource;
import com.segfault03.ideadb.model.InstalledDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Stored drivers must be listed, reused without downloading and never silently replaced. */
class DriverStoreTest {
    @TempDir Path cache;

    @AfterEach void clearCacheOverride() { System.clearProperty("lattice.jdbc.cache"); }

    /** Stages a real packaged driver under a retained version name, so class loading is exercised. */
    private Path stored(DatabaseType type, String version) throws Exception {
        Path jar = DriverCatalog.downloadedJar(type, version);
        Files.createDirectories(jar.getParent());
        String prefix = type == DatabaseType.MYSQL ? "mysql-connector" : "hsqldb-";
        Path source;
        try (var jars = Files.list(Path.of("lib"))) {
            source = jars.filter(path -> path.getFileName().toString().startsWith(prefix)
                            && path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No packaged " + type + " test driver found under lib"));
        }
        Files.copy(source, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    @Test void storedVersionsAreDiscoveredAndListedAfterThePackagedDriver() throws Exception {
        System.setProperty("lattice.jdbc.cache", cache.toString());
        assertEquals("8.4.0", DriverCatalog.versionOf(DatabaseType.MYSQL, "mysql-connector-j-8.4.0.jar"));
        assertEquals("5.1.49", DriverCatalog.versionOf(DatabaseType.MYSQL, "mysql-connector-java-5.1.49.jar"));
        assertEquals("2.7.4-jdk8", DriverCatalog.versionOf(DatabaseType.HSQLDB, "hsqldb-2.7.4-jdk8.jar"));
        assertEquals("", DriverCatalog.versionOf(DatabaseType.MYSQL, "hsqldb-2.7.4-jdk8.jar"));
        assertEquals("", DriverCatalog.versionOf(DatabaseType.MYSQL, "mysql-connector-j-latest.jar"));
        assertEquals("", DriverCatalog.versionOf(DatabaseType.MYSQL, "mysql-connector-j-9.0.0.zip"));
        assertEquals("", DriverCatalog.versionOf(DatabaseType.MYSQL, null));
        assertTrue(DriverCatalog.downloadedVersions(DatabaseType.MYSQL).isEmpty());

        stored(DatabaseType.MYSQL, "8.4.0");
        stored(DatabaseType.MYSQL, "5.1.49");
        stored(DatabaseType.MYSQL, "9.0.0");
        assertEquals(List.of("9.0.0", "8.4.0", "5.1.49"), DriverCatalog.downloadedVersions(DatabaseType.MYSQL));

        List<InstalledDriver> installed = DriverStore.installed(DatabaseType.MYSQL);
        assertEquals(4, installed.size());
        assertTrue(installed.get(0).packaged(), "The packaged driver leads the bundled list");
        assertNull(installed.get(0).jar());
        assertEquals(List.of("9.0.0", "8.4.0", "5.1.49"),
                installed.stream().skip(1).map(InstalledDriver::version).toList());
        assertTrue(installed.stream().skip(1).allMatch(driver -> driver.jar() != null && !driver.packaged()),
                "Stored drivers expose the retained JAR they load from");
        assertEquals("9.0.0 · DOWNLOADED", installed.get(1).label(), "Retained driver hint is uppercase in the available driver list");
    }

    @Test void downloadsAreNeverFetchedTwiceAndUnusableFilesAreNotTrusted() throws Exception {
        System.setProperty("lattice.jdbc.cache", cache.toString());
        Path jar = stored(DatabaseType.MYSQL, "8.4.0");
        byte[] retained = Files.readAllBytes(jar);
        assertTrue(DriverCatalog.isInstalled(DatabaseType.MYSQL, "8.4.0"));
        assertEquals(jar, DriverCatalog.download(DatabaseType.MYSQL, "8.4.0"), "A retained driver must not be downloaded again");
        assertArrayEquals(retained, Files.readAllBytes(jar));

        Files.write(jar, "not a jar".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(DriverCatalog.isInstalled(DatabaseType.MYSQL, "8.4.0"), "A damaged retained file must not be trusted");
        assertFalse(DriverStore.isRetained(DatabaseType.MYSQL, "8.4.0"));
        assertFalse(DriverCatalog.isInstalled(DatabaseType.MYSQL, "../invalid"));
        assertFalse(DriverCatalog.isInstalled(DatabaseType.MYSQL, ""));
    }

    @Test void aBundledSelectionUsesTheStoredJarAndRefusesToFallBackToThePackagedDriver() throws Exception {
        System.setProperty("lattice.jdbc.cache", cache.toString());
        Path jar = stored(DatabaseType.MYSQL, "8.4.0");
        assertEquals(jar, DriverRegistry.retainedJar(DatabaseType.MYSQL, "8.4.0"));
        assertNull(DriverRegistry.retainedJar(DatabaseType.MYSQL, ""));
        assertNull(DriverRegistry.retainedJar(DatabaseType.MYSQL, "5.7.44"));

        var retained = new ConnectionConfig(DatabaseType.MYSQL, "retained");
        retained.setDriverSource(DriverSource.BUNDLED);
        retained.setDriverVersion("8.4.0");
        // A local registry is disposed so Connector/J's cleanup thread does not outlive the test.
        var registry = new DriverRegistry();
        try {
            var selected = registry.getDriver(retained);
            assertEquals(jar.toRealPath(), Path.of(selected.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    "A bundled selection naming a stored driver must load that driver");

            var missing = retained.copy();
            missing.setDriverVersion("5.7.44");
            assertThrows(IllegalStateException.class, () -> registry.getDriver(missing),
                    "A bundled selection must not silently switch to the packaged driver");

            var packaged = retained.copy();
            packaged.setDriverVersion("");
            assertEquals(registry.getDriver(DatabaseType.MYSQL), registry.getDriver(packaged));
        } finally { registry.dispose(); }
    }

    @Test void disappearingArtifactDoesNotEraseTheRegisteredDriverIdentity() throws Exception {
        System.setProperty("lattice.jdbc.cache", cache.toString());
        Path jar = stored(DatabaseType.HSQLDB, "2.7.2");
        var retained = new ConnectionConfig(DatabaseType.HSQLDB, "retained");
        retained.setDriverVersion("2.7.2");
        var manager = new DatabaseConnectionManager();
        try {
            manager.registerConfiguration(retained);
            // Remove it before loading: this also works on Windows, which locks loaded JARs.
            Files.delete(jar);
            var packaged = retained.copy();
            packaged.setDriverVersion("");
            manager.registerConfiguration(packaged);
            assertThrows(java.sql.SQLException.class, () -> manager.openConnection(retained),
                    "An editor using the removed selection must be rejected after switching to bundled");
            packaged.setHsqlMode(com.segfault03.ideadb.model.HsqlMode.MEM);
            packaged.setDatabaseName("identity_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            manager.registerConfiguration(packaged);
            var connection = manager.getConnection(packaged);
            var missing = packaged.copy();
            missing.setDriverVersion("2.7.2");
            manager.registerConfiguration(missing);
            assertTrue(connection.isClosed(), "Selecting a now-missing explicit version still retires the old session");
            assertThrows(java.sql.SQLException.class, () -> manager.getConnection(packaged));
            assertThrows(IllegalStateException.class, () -> manager.openConnection(missing),
                    "Missing selected drivers must not reuse or fall back to the packaged driver");
        } finally { manager.dispose(); }
    }

    @Test void corruptionInsideAReadableZipIsRejectedAndOnlyOwnedArtifactsAreDeleted() throws Exception {
        System.setProperty("lattice.jdbc.cache", cache.toString());
        Path retained = stored(DatabaseType.HSQLDB, "2.7.2");
        // Build a valid STORED entry, then damage the bytes without changing the ZIP directory / CRC.
        byte[] bytecode;
        try (var jar = new java.util.jar.JarFile(retained.toFile())) {
            bytecode = jar.getInputStream(jar.getJarEntry("org/hsqldb/jdbc/JDBCDriver.class")).readAllBytes();
        }
        var entry = new java.util.zip.ZipEntry("org/hsqldb/jdbc/JDBCDriver.class");
        entry.setMethod(java.util.zip.ZipEntry.STORED);
        entry.setSize(bytecode.length);
        var crc = new java.util.zip.CRC32(); crc.update(bytecode); entry.setCrc(crc.getValue());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(retained))) {
            zip.putNextEntry(entry); zip.write(bytecode); zip.closeEntry();
        }
        assertNotNull(DriverCatalog.validateJar(DatabaseType.HSQLDB, retained));
        byte[] archive = Files.readAllBytes(retained);
        int dataStart = 30 + entry.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        archive[dataStart + 8] ^= 1;
        Files.write(retained, archive);
        Files.setLastModifiedTime(retained, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
        Path external = cache.resolve("hsqldb-2.7.2.jar"); Files.copy(retained, external);
        assertThrows(java.io.IOException.class, () -> DriverCatalog.validateJar(DatabaseType.HSQLDB, external));
        assertTrue(Files.exists(external), "Validation must not delete Maven or user-supplied JARs");
        assertFalse(DriverCatalog.isInstalled(DatabaseType.HSQLDB, "2.7.2"));
        assertFalse(Files.exists(retained), "A corrupt retained JAR must be removed so the version can be downloaded again");
    }

    @Test void cancelledValidationCannotDeleteAValidRetainedArtifact() throws Exception {
        System.setProperty("lattice.jdbc.cache", cache.toString());
        Path jar = stored(DatabaseType.HSQLDB, "2.7.2");
        Thread.currentThread().interrupt();
        try {
            assertFalse(DriverCatalog.isInstalled(DatabaseType.HSQLDB, "2.7.2"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(Files.exists(jar), "Cancellation is not evidence of corruption");
        } finally { Thread.interrupted(); }
        assertTrue(DriverCatalog.isInstalled(DatabaseType.HSQLDB, "2.7.2"));
    }

    @Test void versionOrderingMatchesMavenReleaseOrder() {
        assertTrue(DriverCatalog.compareVersionsDescending("9.0.0", "8.4.0") < 0);
        assertTrue(DriverCatalog.compareVersionsDescending("8.0.33", "8.0.9") < 0);
        assertTrue(DriverCatalog.compareVersionsDescending("10.0.0", "9.9.9") < 0);
        assertTrue(DriverCatalog.compareVersionsDescending("2.7.4-jdk8", "2.7.4") < 0);
        assertTrue(DriverCatalog.compareVersionsDescending("2.7.4", "2.7.4-jdk8") > 0);
        assertEquals(0, DriverCatalog.compareVersionsDescending("8.4.0", "8.4.0"));
    }

    @Test void unrelatedFilesInTheCacheAreIgnored() throws Exception {
        System.setProperty("lattice.jdbc.cache", cache.toString());
        Path mysql = DriverCatalog.downloadedJar(DatabaseType.MYSQL, "8.4.0").getParent();
        Path hsqldb = DriverCatalog.downloadedJar(DatabaseType.HSQLDB, "2.7.4-jdk8").getParent();
        for (Path directory : List.of(mysql, hsqldb)) {
            Files.createDirectories(directory);
            for (String name : List.of("notes.txt", "mysql-connector-j-latest.jar", "jdbc-1234.part"))
                Files.write(directory.resolve(name), new byte[]{1});
        }
        assertEquals(List.of(), DriverCatalog.downloadedVersions(DatabaseType.MYSQL));
        assertEquals(List.of(), DriverCatalog.downloadedVersions(DatabaseType.HSQLDB));
        assertEquals(1, DriverStore.installed(DatabaseType.MYSQL).size(), "Only the packaged driver is offered");
    }
}
