package com.segfault03.ideadb.sql.discovery;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.HsqlMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A database that a project configuration file points at, and which file said so.
 *
 * @param user the user name the file gives for it, or null; passwords are never read
 */
public record DiscoveredConnection(@NotNull ParsedJdbcUrl url, @NotNull String fileName, @NotNull String path, @Nullable String user) {
    public DiscoveredConnection(@NotNull ParsedJdbcUrl url, @NotNull String fileName, @NotNull String path) { this(url, fileName, path, null); }

    /** {@code PostgreSQL localhost:5432/shop (application.yml)}: the target and where it was found, for a person. */
    public @NotNull String describe() { return url.describe() + " (" + fileName + ")"; }

    /** {@code PostgreSQL billing on db.internal}: short enough for a banner; the port is named only when it is not the default. */
    public @NotNull String summary() {
        StringBuilder text = new StringBuilder(url.type().getDisplayName());
        boolean embedded = url.mode() == HsqlMode.MEM || url.mode() == HsqlMode.FILE;
        if (url.mode() == HsqlMode.MEM) text.append(" in-memory");
        else if (url.mode() == HsqlMode.FILE) text.append(" file");
        String database = url.database();
        if (database != null && !database.isBlank()) text.append(' ').append(database);
        if (!embedded && url.host() != null) {
            text.append(" on ").append(url.host());
            if (url.port() > 0) text.append(':').append(url.port());
        }
        return text.toString();
    }

    /** A connection to create from this: the server, port and database, and the user name if the file gave one; never a password. */
    public @NotNull ConnectionConfig toConnection() {
        ConnectionConfig config = url.toConnection(suggestedName());
        if (user != null && !user.isBlank()) config.setUser(user);
        return config;
    }

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
