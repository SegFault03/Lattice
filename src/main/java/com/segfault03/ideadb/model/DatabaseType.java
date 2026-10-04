package com.segfault03.ideadb.model;

public enum DatabaseType {
    MYSQL("MySQL", 3306, "root", "com.mysql.cj.jdbc.Driver"),
    HSQLDB("HSQLDB", 9001, "SA", "org.hsqldb.jdbc.JDBCDriver");

    private final String displayName;
    private final int defaultPort;
    private final String defaultUser;
    private final String driverClassName;

    DatabaseType(String displayName, int defaultPort, String defaultUser, String driverClassName) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.defaultUser = defaultUser;
        this.driverClassName = driverClassName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getDefaultUser() {
        return defaultUser;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
