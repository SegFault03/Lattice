package com.segfault03.ideadb.model;

import com.segfault03.ideadb.provider.DatabaseProviders;

/** Stable persisted IDs; behavior is supplied by registered providers. */
public enum DatabaseType {
    MYSQL("MySQL"), HSQLDB("HSQLDB"), POSTGRESQL("PostgreSQL"), H2("H2");
    private final String displayName;
    DatabaseType(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
    public int getDefaultPort() { return DatabaseProviders.get(this).defaultPort(); }
    public String getDefaultUser() { return DatabaseProviders.get(this).defaultUser(); }
    public String getDriverClassName() { return DatabaseProviders.get(this).driver().driverClasses().getFirst(); }
    @Override public String toString() { return displayName; }
}
