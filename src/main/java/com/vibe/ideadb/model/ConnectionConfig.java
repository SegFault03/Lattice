package com.vibe.ideadb.model;

import java.util.Objects;
import java.util.UUID;

public class ConnectionConfig {
    private String id = UUID.randomUUID().toString();
    private String name = "New Connection";
    private DatabaseType type = DatabaseType.MYSQL;
    private HsqlMode hsqlMode = HsqlMode.MEM;
    private String host = "localhost";
    private int port = 3306;
    private String databaseName = "";
    private String user = "root";
    private String password = "";
    private String customUrl = "";
    private boolean autoCommit = true;
    private DriverSource driverSource = DriverSource.BUNDLED;
    private String driverVersion = "";
    private String driverJarPath = "";

    public ConnectionConfig() {
    }

    public ConnectionConfig(DatabaseType type, String name) {
        this.type = type;
        this.name = name;
        if (type == DatabaseType.HSQLDB) {
            this.hsqlMode = HsqlMode.SERVER;
            this.host = "localhost";
            this.port = 9001;
            this.user = "SA";
            this.password = "";
            this.databaseName = "testdb";
        } else {
            this.host = "localhost";
            this.port = 3306;
            this.user = "root";
            this.databaseName = "";
        }
    }

    public String buildJdbcUrl() {
        if (customUrl != null && !customUrl.trim().isEmpty()) {
            return customUrl.trim();
        }

        if (type == DatabaseType.MYSQL) {
            String db = (databaseName != null && !databaseName.trim().isEmpty()) ? databaseName.trim() : "";
            String h = (host != null && !host.trim().isEmpty()) ? host.trim() : "localhost";
            int p = port > 0 ? port : 3306;
            return "jdbc:mysql://" + h + ":" + p + "/" + db + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&useLegacyDatetimeCode=false&forceConnectionTimeZoneToSession=true&characterEncoding=utf8";
        } else if (type == DatabaseType.HSQLDB) {
            String db = (databaseName != null && !databaseName.trim().isEmpty()) ? databaseName.trim() : "testdb";
            if (hsqlMode == HsqlMode.MEM) {
                return "jdbc:hsqldb:mem:" + db + ";shutdown=true";
            } else if (hsqlMode == HsqlMode.FILE) {
                // normalize backslashes for JDBC url
                String normalizedPath = db.replace("\\", "/");
                return "jdbc:hsqldb:file:" + normalizedPath + ";shutdown=true";
            } else { // SERVER
                String h = (host != null && !host.trim().isEmpty()) ? host.trim() : "localhost";
                int p = port > 0 ? port : 9001;
                return "jdbc:hsqldb:hsql://" + h + ":" + p + "/" + db;
            }
        }
        return "";
    }

    public ConnectionConfig copy() {
        ConnectionConfig c = new ConnectionConfig();
        c.id = this.id;
        c.name = this.name;
        c.type = this.type;
        c.hsqlMode = this.hsqlMode;
        c.host = this.host;
        c.port = this.port;
        c.databaseName = this.databaseName;
        c.user = this.user;
        c.password = this.password;
        c.customUrl = this.customUrl;
        c.autoCommit = this.autoCommit;
        c.driverSource = this.driverSource;
        c.driverVersion = this.driverVersion;
        c.driverJarPath = this.driverJarPath;
        return c;
    }

    public DriverSource getDriverSource() { return driverSource == null ? DriverSource.BUNDLED : driverSource; }
    public void setDriverSource(DriverSource source) { driverSource = source; }
    public String getDriverVersion() { return Objects.requireNonNullElse(driverVersion, ""); }
    public void setDriverVersion(String version) { driverVersion = version; }
    public String getDriverJarPath() { return Objects.requireNonNullElse(driverJarPath, ""); }
    public void setDriverJarPath(String path) { driverJarPath = path; }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public DatabaseType getType() { return type; }
    public void setType(DatabaseType type) { this.type = type; }

    public HsqlMode getHsqlMode() { return hsqlMode; }
    public void setHsqlMode(HsqlMode hsqlMode) { this.hsqlMode = hsqlMode; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getCustomUrl() { return customUrl; }
    public void setCustomUrl(String customUrl) { this.customUrl = customUrl; }

    public boolean isAutoCommit() { return autoCommit; }
    public void setAutoCommit(boolean autoCommit) { this.autoCommit = autoCommit; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConnectionConfig that = (ConnectionConfig) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + " (" + type.getDisplayName() + ")";
    }
}
