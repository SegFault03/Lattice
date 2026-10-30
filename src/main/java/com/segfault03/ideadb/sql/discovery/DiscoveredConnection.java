package com.segfault03.ideadb.sql.discovery;

import org.jetbrains.annotations.NotNull;

/** A database that a project configuration file points at, and which file said so. */
public record DiscoveredConnection(@NotNull ParsedJdbcUrl url, @NotNull String fileName, @NotNull String path) {
    /** {@code PostgreSQL localhost:5432/shop (application.yml)}: the target and where it was found, for a person. */
    public @NotNull String describe() { return url.describe() + " (" + fileName + ")"; }

    /** What a person would call this connection: the database's last path segment, else the type. */
    public @NotNull String suggestedName() {
        String database = url.database();
        if (database != null && !database.isBlank()) {
            String name = database.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            name = name.substring(name.lastIndexOf(':') + 1);
            if (!name.isBlank() && !name.equals("~")) return name + " (" + url.type().getDisplayName() + ")";
        }
        return url.type().getDisplayName() + " from " + fileName;
    }
}
