package com.segfault03.ideadb.sql.discovery;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.HsqlMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Where a JDBC URL points, and nothing else: the driver family, how it connects, the server, the port and the database.
 * Credentials, query parameters and driver options are dropped when the URL is parsed, so nothing read from a project's
 * files can carry a secret into a saved connection, a log or a message. A part that the file leaves to a placeholder
 * which could not be resolved is {@code null} and matches anything.
 *
 * @param mode     how an embedded or server database is reached; null for MySQL and PostgreSQL
 * @param host     the server, lower case; null for embedded databases or when unknown
 * @param port     the server port; 0 when the URL names none (the default for the type applies)
 * @param database the database name, or the path of an embedded one; null when unknown
 */
public record ParsedJdbcUrl(@NotNull DatabaseType type, @Nullable HsqlMode mode, @Nullable String host, int port, @Nullable String database) {
    /** The default port is the same as no port: the two spellings are one URL. */
    public ParsedJdbcUrl {
        if (port == type.getDefaultPort() || (mode == HsqlMode.MEM || mode == HsqlMode.FILE)) port = 0;
    }

    private static final Set<String> THIS_MACHINE = Set.of("localhost", "127.0.0.1", "::1", "0.0.0.0", "[::1]");

    /** The port the type uses when the URL does not say. */
    public int effectivePort() { return port > 0 ? port : type.getDefaultPort(); }

    /** Whether both URLs name the same database. Unknown parts match anything; known parts must agree. */
    public boolean sameDatabaseAs(@NotNull ParsedJdbcUrl other) {
        if (type != other.type) return false;
        boolean embedded = mode == HsqlMode.MEM || mode == HsqlMode.FILE;
        boolean otherEmbedded = other.mode == HsqlMode.MEM || other.mode == HsqlMode.FILE;
        if (mode != null && other.mode != null && mode != other.mode) return false;
        if (embedded != otherEmbedded && mode != null && other.mode != null) return false;
        if (!embedded && !otherEmbedded) {
            if (host != null && other.host != null && !sameMachine(host, other.host)) return false;
            if (host != null && other.host != null && effectivePort() != other.effectivePort()) return false;
        }
        return namesAgree(database, other.database, mode == HsqlMode.FILE || other.mode == HsqlMode.FILE);
    }

    private static boolean sameMachine(String a, String b) {
        return a.equalsIgnoreCase(b) || (THIS_MACHINE.contains(a.toLowerCase(Locale.ROOT)) && THIS_MACHINE.contains(b.toLowerCase(Locale.ROOT)));
    }

    private static boolean namesAgree(@Nullable String a, @Nullable String b, boolean path) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return true;
        return path ? normalizePath(a).equals(normalizePath(b)) : a.equalsIgnoreCase(b);
    }

    private static String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        for (String suffix : new String[] {".mv.db", ".trace.db", ".script", ".properties", ".data"}) {
            if (normalized.endsWith(suffix)) normalized = normalized.substring(0, normalized.length() - suffix.length());
        }
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized.toLowerCase(Locale.ROOT);
    }

    /** Whether a saved connection points at this database. */
    public boolean matches(@NotNull ConnectionConfig config) {
        ParsedJdbcUrl saved = JdbcUrls.parse(config.buildJdbcUrl());
        return saved != null && sameDatabaseAs(saved);
    }

    /** Short text for a person, built from the parsed parts only: {@code PostgreSQL localhost:5432/shop}. */
    public @NotNull String describe() {
        StringBuilder text = new StringBuilder(type.getDisplayName());
        if (mode == HsqlMode.MEM) text.append(" in-memory");
        else if (mode == HsqlMode.FILE) text.append(" file");
        if (host != null && mode != HsqlMode.MEM && mode != HsqlMode.FILE) text.append(' ').append(host).append(':').append(effectivePort());
        if (database != null && !database.isEmpty()) text.append(host != null && mode != HsqlMode.MEM && mode != HsqlMode.FILE ? "/" : " ").append(database);
        return text.toString();
    }

    /** A connection to create from this URL: server, port and database only. The user and password are left for the person to enter. */
    public @NotNull ConnectionConfig toConnection(@NotNull String name) {
        ConnectionConfig config = new ConnectionConfig(type, name);
        config.setUser("");
        config.setPassword("");
        if (mode != null) config.setHsqlMode(mode);
        if (host != null) config.setHost(host);
        if (port > 0) config.setPort(port);
        config.setDatabaseName(database == null ? "" : database);
        return config;
    }

    @Override public String toString() { return describe(); }
}
