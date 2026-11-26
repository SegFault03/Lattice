package com.segfault03.ideadb.model;

/** Where a connection points, shown beside it so production is never mistaken for a scratch database. */
public enum Environment {
    NONE("None"), LOCAL("Local"), DEVELOPMENT("Development"), STAGING("Staging"), PRODUCTION("Production");

    private final String displayName;

    Environment(String displayName) { this.displayName = displayName; }

    public String getDisplayName() { return displayName; }

    /** The lowercase tag shown in the explorer and search; empty for none. */
    public String tag() { return this == NONE ? "" : displayName.toLowerCase(java.util.Locale.ROOT); }

    @Override public String toString() { return displayName; }
}
