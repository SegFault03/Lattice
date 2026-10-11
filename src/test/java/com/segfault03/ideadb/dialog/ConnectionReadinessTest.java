package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionReadinessTest {
    @TempDir Path directory;

    @Test void mysqlRequiresHostPortAndUserButAllowsServerBrowsingAndAnEmptyPassword() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "");
        assertNull(ConnectionReadiness.problem(config, false));
        config.setHost("");
        assertEquals("Enter a host", ConnectionReadiness.problem(config, false));
        config.setHost("localhost");
        for (int port : new int[]{0, -1, 65536}) {
            config.setPort(port);
            assertEquals("Enter a port from 1 to 65535", ConnectionReadiness.problem(config, false));
        }
        config.setPort(3306);
        config.setUser(" ");
        assertEquals("Enter a user name", ConnectionReadiness.problem(config, false));
    }

    @Test void hsqlRequiresTheSelectedModesFieldsOnly() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.HSQLDB, "Scratch");
        for (HsqlMode mode : HsqlMode.values()) {
            config.setHsqlMode(mode);
            config.setDatabaseName("");
            assertNotNull(ConnectionReadiness.problem(config, false));
            config.setDatabaseName("scratch");
            config.setHost("localhost");
            config.setPort(9001);
            assertNull(ConnectionReadiness.problem(config, false));
            config.setHost("");
            config.setPort(0);
            assertEquals(mode == HsqlMode.SERVER ? "Enter a host" : null,
                    ConnectionReadiness.problem(config, false));
        }
    }

    @Test void customJdbcUrlCanSupplyItsOwnCredentials() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "Custom");
        config.setHost("");
        config.setUser("");
        config.setPort(0);
        for (String url : new String[]{"", "localhost", "jdbc:"}) {
            config.setCustomUrl(url);
            assertEquals("Enter a JDBC URL", ConnectionReadiness.problem(config, true));
        }
        config.setCustomUrl("jdbc:mysql://localhost/shop?user=root");
        assertNull(ConnectionReadiness.problem(config, true));
    }

    @Test void localJarMustBeAnExistingReadableJarFile() throws Exception {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "Local");
        config.setDriverSource(DriverSource.LOCAL_JAR);
        assertNotNull(ConnectionReadiness.problem(config, false));
        for (String path : new String[]{directory.resolve("missing.jar").toString(),
                directory.toString(), directory.getRoot().toString(), "invalid\u0000.jar"}) {
            config.setDriverJarPath(path);
            assertNotNull(ConnectionReadiness.problem(config, false));
        }
        Path wrongExtension = Files.createFile(directory.resolve("driver.txt"));
        config.setDriverJarPath(wrongExtension.toString());
        assertNotNull(ConnectionReadiness.problem(config, false));
        config.setDriverJarPath(Files.createFile(directory.resolve("driver.JAR")).toString());
        assertNull(ConnectionReadiness.problem(config, false));
    }

    @Test void downloadSourceRequiresADownloadedDriver() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "Download");
        config.setDriverSource(DriverSource.DOWNLOAD);
        config.setDriverVersion("999.999.999");
        assertNotNull(ConnectionReadiness.problem(config, false));
        config.setDriverVersion("../invalid");
        assertNotNull(ConnectionReadiness.problem(config, false));
        config.setDriverSource(DriverSource.BUNDLED);
        config.setDriverVersion("");
        assertNull(ConnectionReadiness.problem(config, false));
    }

    @Test void bundledDriverWithoutAStoredJarIsReportedInsteadOfSilentlyReplaced() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "Retained");
        config.setDriverSource(DriverSource.BUNDLED);
        config.setDriverVersion("999.999.999");
        assertNotNull(ConnectionReadiness.problem(config, false), "A missing stored driver must not fall back to the packaged one");
        config.setDriverVersion("../invalid");
        assertNotNull(ConnectionReadiness.problem(config, false));
    }
}
