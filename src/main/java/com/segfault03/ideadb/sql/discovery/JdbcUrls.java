package com.segfault03.ideadb.sql.discovery;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.HsqlMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the JDBC URLs of the supported databases. Only the target is kept (see {@link ParsedJdbcUrl}): user names,
 * passwords, query strings and driver options never leave this class. The character {@link #UNKNOWN} stands where a
 * placeholder could not be resolved.
 */
public final class JdbcUrls {
    /** Marks a part of a URL that a placeholder left unknown. */
    public static final char UNKNOWN = '\u0000';

    private static final Pattern TESTCONTAINERS = Pattern.compile("^jdbc:tc:([a-z]+)(?::[^/:]*)?:(//.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVER_URL = Pattern.compile("^//(?:[^/@]*@)?(\\[[^\\]]*\\]|[^/:,?;]*)(?::(\\d*))?[^/]*(?:/([^?;]*))?.*$");

    private JdbcUrls() {}

    public static @Nullable ParsedJdbcUrl parse(@Nullable String url) {
        if (url == null) return null;
        String text = url.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:")) return null;
        // Wrappers such as p6spy and Testcontainers prefix the real URL.
        if (lower.startsWith("jdbc:p6spy:")) return parse("jdbc:" + text.substring("jdbc:p6spy:".length()));
        Matcher container = TESTCONTAINERS.matcher(text);
        if (container.matches()) {
            DatabaseType type = serverType(container.group(1).toLowerCase(Locale.ROOT));
            return type == null ? null : server(type, container.group(2));
        }
        String rest = text.substring("jdbc:".length());
        String restLower = rest.toLowerCase(Locale.ROOT);
        for (String scheme : new String[] {"mysql:replication:", "mysql:loadbalance:", "mysql:aws:", "mysql:", "mariadb:"}) {
            if (restLower.startsWith(scheme)) return server(DatabaseType.MYSQL, rest.substring(scheme.length()));
        }
        if (restLower.startsWith("postgresql:")) return server(DatabaseType.POSTGRESQL, rest.substring("postgresql:".length()));
        if (restLower.startsWith("h2:")) return h2(rest.substring("h2:".length()));
        if (restLower.startsWith("hsqldb:")) return hsqldb(rest.substring("hsqldb:".length()));
        return null;
    }

    private static @Nullable DatabaseType serverType(String name) {
        return switch (name) {
            case "mysql", "mariadb" -> DatabaseType.MYSQL;
            case "postgresql", "postgres" -> DatabaseType.POSTGRESQL;
            default -> null;
        };
    }

    // ---- MySQL and PostgreSQL --------------------------------------------------------------------------------------

    private static @Nullable ParsedJdbcUrl server(DatabaseType type, String rest) {
        if (!rest.startsWith("//")) {
            // jdbc:postgresql:dbname is the local server's database
            String database = unknownIfMarked(stripAfter(rest, "?;"));
            return type == DatabaseType.POSTGRESQL && !rest.isEmpty() ? new ParsedJdbcUrl(type, null, "localhost", 0, database) : null;
        }
        Matcher matcher = SERVER_URL.matcher(rest);
        if (!matcher.matches()) return null;
        String host = matcher.group(1);
        host = host == null || host.isEmpty() ? (rest.startsWith("///") ? null : "localhost") : unknownIfMarked(host.toLowerCase(Locale.ROOT));
        int port = parsePort(matcher.group(2));
        String database = unknownIfMarked(matcher.group(3) == null ? "" : decode(matcher.group(3)));
        return new ParsedJdbcUrl(type, null, host, port, database);
    }

    // ---- H2 --------------------------------------------------------------------------------------------------------

    private static @Nullable ParsedJdbcUrl h2(String rest) {
        String lower = rest.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mem:")) return new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.MEM, null, 0, unknownIfMarked(stripAfter(rest.substring(4), ";")));
        if (lower.startsWith("file:")) return new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.FILE, null, 0, unknownIfMarked(stripAfter(rest.substring(5), ";")));
        for (String scheme : new String[] {"tcp:", "ssl:"}) {
            if (lower.startsWith(scheme)) return h2Server(rest.substring(scheme.length()));
        }
        if (lower.startsWith("zip:") || lower.startsWith("async:") || lower.startsWith("nio:") || lower.startsWith("nioMapped:".toLowerCase(Locale.ROOT))) return null;
        // jdbc:h2:~/test, jdbc:h2:./data/db, jdbc:h2:/abs/path and jdbc:h2:name are files
        return rest.isEmpty() ? null : new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.FILE, null, 0, unknownIfMarked(stripAfter(rest, ";")));
    }

    private static @Nullable ParsedJdbcUrl h2Server(String rest) {
        Matcher matcher = Pattern.compile("^//(\\[[^\\]]*\\]|[^/:;]*)(?::(\\d*))?(?:/([^;]*))?.*$").matcher(rest);
        if (!matcher.matches()) return null;
        String host = unknownIfMarked(matcher.group(1).toLowerCase(Locale.ROOT));
        return new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.SERVER, host == null || host.isEmpty() ? "localhost" : host, parsePort(matcher.group(2)),
                unknownIfMarked(matcher.group(3) == null ? "" : matcher.group(3)));
    }

    // ---- HSQLDB ----------------------------------------------------------------------------------------------------

    private static @Nullable ParsedJdbcUrl hsqldb(String rest) {
        String lower = rest.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mem:")) return new ParsedJdbcUrl(DatabaseType.HSQLDB, HsqlMode.MEM, null, 0, unknownIfMarked(stripAfter(rest.substring(4), ";")));
        if (lower.startsWith("file:")) return new ParsedJdbcUrl(DatabaseType.HSQLDB, HsqlMode.FILE, null, 0, unknownIfMarked(stripAfter(rest.substring(5), ";")));
        for (String scheme : new String[] {"hsqls:", "hsql:", "https:", "http:"}) {
            if (!lower.startsWith(scheme)) continue;
            Matcher matcher = Pattern.compile("^//(\\[[^\\]]*\\]|[^/:;]*)(?::(\\d*))?(?:/([^;]*))?.*$").matcher(rest.substring(scheme.length()));
            if (!matcher.matches()) return null;
            String host = unknownIfMarked(matcher.group(1).toLowerCase(Locale.ROOT));
            return new ParsedJdbcUrl(DatabaseType.HSQLDB, HsqlMode.SERVER, host == null || host.isEmpty() ? "localhost" : host, parsePort(matcher.group(2)),
                    unknownIfMarked(matcher.group(3) == null ? "" : matcher.group(3)));
        }
        return null;
    }

    // ---- pieces ----------------------------------------------------------------------------------------------------

    private static int parsePort(@Nullable String port) {
        if (port == null || port.isEmpty() || port.indexOf(UNKNOWN) >= 0) return 0;
        try { return Integer.parseInt(port); } catch (NumberFormatException invalid) { return 0; }
    }

    /** A part with any unresolved placeholder in it is unknown as a whole. */
    private static @Nullable String unknownIfMarked(@Nullable String part) {
        return part == null || part.indexOf(UNKNOWN) >= 0 ? null : part;
    }

    private static String stripAfter(String text, String delimiters) {
        for (int i = 0; i < text.length(); i++) if (delimiters.indexOf(text.charAt(i)) >= 0) return text.substring(0, i);
        return text;
    }

    private static String decode(String part) {
        try { return java.net.URLDecoder.decode(part, java.nio.charset.StandardCharsets.UTF_8); } catch (IllegalArgumentException invalid) { return part; }
    }
}
