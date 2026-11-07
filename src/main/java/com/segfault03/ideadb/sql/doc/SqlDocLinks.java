package com.segfault03.ideadb.sql.doc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** The links inside a documentation card: {@code lattice-sql:<kind>/<connection>/<namespace>/<table>[/<column>]}. */
final class SqlDocLinks {
    static final String SCHEME = "lattice-sql:";

    private SqlDocLinks() {}

    /** A link that shows the card for a target. */
    static @NotNull String show(@NotNull String connectionId, @NotNull SqlTarget target) {
        return switch (target) {
            case SqlTarget.Schema schema -> build("schema", connectionId, schema.namespace());
            case SqlTarget.Table table -> build("table", connectionId, table.namespace(), table.table());
            case SqlTarget.Column column -> build("column", connectionId, column.namespace(), column.table(), column.column());
            case SqlTarget.Alias alias -> build("table", connectionId, alias.namespace(), alias.table());
        };
    }

    /** A link that opens the table's data editor. */
    static @NotNull String open(@NotNull String connectionId, @NotNull String namespace, @NotNull String table) {
        return build("open", connectionId, namespace, table);
    }

    record Parsed(@NotNull String kind, @NotNull String connectionId, @NotNull SqlTarget target) {}

    static @Nullable Parsed parse(@NotNull String url) {
        if (!url.startsWith(SCHEME)) return null;
        String[] parts = url.substring(SCHEME.length()).split("/", -1);
        if (parts.length < 3) return null;
        String[] decoded = new String[parts.length];
        for (int i = 0; i < parts.length; i++) decoded[i] = URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
        String kind = decoded[0];
        SqlTarget target = switch (kind) {
            case "schema" -> parts.length == 3 ? new SqlTarget.Schema(decoded[2]) : null;
            case "table", "open" -> parts.length == 4 ? new SqlTarget.Table(decoded[2], decoded[3]) : null;
            case "column" -> parts.length == 5 ? new SqlTarget.Column(decoded[2], decoded[3], decoded[4]) : null;
            default -> null;
        };
        return target == null ? null : new Parsed(kind, decoded[1], target);
    }

    private static String build(String kind, String... parts) {
        StringBuilder url = new StringBuilder(SCHEME).append(kind);
        for (String part : parts) url.append('/').append(URLEncoder.encode(part, StandardCharsets.UTF_8));
        return url.toString();
    }
}
