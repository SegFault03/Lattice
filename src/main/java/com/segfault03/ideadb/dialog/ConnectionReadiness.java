package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.DriverSource;
import com.segfault03.ideadb.model.HsqlMode;
import com.segfault03.ideadb.service.DriverCatalog;
import com.segfault03.ideadb.service.DriverStore;

import java.nio.file.Files;
import java.nio.file.Path;

/** Cheap form checks; driver validation and network access remain in the connection task. */
final class ConnectionReadiness {
    private ConnectionReadiness() {}

    static String problem(ConnectionConfig config, boolean customUrlMode) {
        if (customUrlMode) {
            String url = config.getCustomUrl().trim();
            if (!url.startsWith("jdbc:") || url.length() <= 5) return "Enter a JDBC URL";
            // Credentials may be embedded in the URL; a separate user/password is optional.
        } else {
            boolean server = config.getType() == DatabaseType.MYSQL || config.getHsqlMode() == HsqlMode.SERVER;
            if (server) {
                if (config.getHost().isBlank()) return "Enter a host";
                if (config.getPort() < 1 || config.getPort() > 65535) return "Enter a port from 1 to 65535";
            }
            if (config.getType() == DatabaseType.HSQLDB && config.getDatabaseName().isBlank())
                return config.getHsqlMode() == HsqlMode.FILE ? "Choose a database path" : "Enter a database name";
            // A MySQL database and passwords may be empty (server browsing / passwordless accounts).
            if (config.getUser().isBlank()) return "Enter a user name";
        }
        try {
            if (config.getDriverSource() == DriverSource.LOCAL_JAR) {
                if (config.getDriverJarPath().isBlank()) return "Choose a local driver JAR";
                Path jar = Path.of(config.getDriverJarPath());
                if (jar.getFileName() == null || !jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                        || !Files.isRegularFile(jar) || !Files.isReadable(jar)) return "Choose an existing, readable driver JAR";
            } else if (config.getDriverSource() == DriverSource.DOWNLOAD) {
                Path jar = DriverCatalog.downloadedJar(config.getType(), config.getDriverVersion());
                if (!Files.isRegularFile(jar) || !Files.isReadable(jar)) return "Download the selected driver first";
            } else if (config.getDriverSource() == DriverSource.BUNDLED && !config.getDriverVersion().isBlank()
                    && !DriverStore.isAvailable(config.getType(), config.getDriverVersion(), config.getDriverJarPath())) {
                return "The selected driver is no longer available; download or discover it again";
            }
        } catch (IllegalArgumentException | SecurityException error) {
            return "Choose a valid driver file or version";
        }
        return null;
    }
}
