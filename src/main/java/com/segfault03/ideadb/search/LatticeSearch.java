package com.segfault03.ideadb.search;

import com.intellij.psi.codeStyle.MinusculeMatcher;
import com.intellij.psi.codeStyle.NameUtil;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.state.MetadataCacheState.CachedTable;
import com.segfault03.ideadb.state.RecentItemsState;

import java.util.*;
import java.util.function.Function;

/**
 * Lattice's Search Everywhere results: recent items, tables in schemas Lattice has already loaded, connections, then
 * actions. Searches only local state; it never contacts a database.
 */
public final class LatticeSearch {
    /** Without a query only recent items are listed; with one, tables are capped so a large schema cannot flood the list. */
    static final int RECENT_LIMIT = 20, TABLE_LIMIT = 50;

    public enum Kind { RECENT, TABLE, CONNECTION, CONSOLE, NEW_CONNECTION }

    public record Item(Kind kind, String title, String context, ConnectionConfig connection, String database,
                       TableMetadata table, RecentItemsState.Item recent, DatabaseType type) {
        boolean isView() { return table != null && table.isView(); }
    }

    private LatticeSearch() {}

    /** Title of a recent item: the schema-qualified table name, or Console. */
    public static String recentTitle(RecentItemsState.Item item) {
        if (item.kind == RecentItemsState.Kind.CONSOLE) return "Console";
        return item.schema == null || item.schema.isBlank() ? item.name : item.schema + "." + item.name;
    }

    /** Matcher for highlighting a result title; null for an empty query. */
    public static MinusculeMatcher matcher(String pattern) {
        String query = pattern == null ? "" : pattern.trim();
        return query.isEmpty() ? null : NameUtil.buildMatcher("*" + query, NameUtil.MatchingCaseSensitivity.NONE);
    }

    public static List<Item> search(String pattern, List<ConnectionConfig> connections, List<RecentItemsState.Item> recents,
                                    Function<ConnectionConfig, List<CachedTable>> tables) {
        MinusculeMatcher matcher = matcher(pattern);
        Map<String, ConnectionConfig> byId = new LinkedHashMap<>();
        for (ConnectionConfig connection : connections) byId.putIfAbsent(connection.getId(), connection);
        List<Item> results = new ArrayList<>();

        Set<String> recentTables = new HashSet<>();
        for (RecentItemsState.Item recent : recents) {
            ConnectionConfig connection = byId.get(recent.connectionId);
            if (connection == null) continue;
            String title = recentTitle(recent);
            if (recent.kind == RecentItemsState.Kind.TABLE) recentTables.add(tableKey(connection.getId(), recent.database, recent.name));
            if (matcher != null && !matcher.matches(title)) continue;
            if (matcher == null && results.size() >= RECENT_LIMIT) continue;
            TableMetadata table = recent.kind == RecentItemsState.Kind.TABLE
                    ? new TableMetadata(recent.catalog, recent.schema, recent.name, recent.tableType) : null;
            results.add(new Item(Kind.RECENT, title, connection.getName() + " · recent", connection, recent.database, table, recent, connection.getType()));
        }

        if (matcher == null) {
            if (!results.isEmpty()) return results;
            for (ConnectionConfig connection : byId.values()) results.add(connectionItem(connection));
            if (results.isEmpty()) for (DatabaseType type : DatabaseType.values()) results.add(newConnectionItem(type));
            return results;
        }

        record Scored(Item item, int degree) {}
        List<Scored> tableMatches = new ArrayList<>();
        for (ConnectionConfig connection : byId.values()) {
            for (CachedTable cached : tables.apply(connection)) {
                TableMetadata table = cached.metadata();
                if (recentTables.contains(tableKey(connection.getId(), cached.namespace(), table.getName()))) continue;
                String title = table.getSchema() == null || table.getSchema().isBlank() ? table.getName() : table.getSchema() + "." + table.getName();
                if (!matcher.matches(title)) continue;
                tableMatches.add(new Scored(new Item(Kind.TABLE, title, connection.getName(), connection, cached.namespace(), table, null,
                        connection.getType()), Math.max(matcher.matchingDegree(table.getName()), matcher.matchingDegree(title))));
            }
        }
        tableMatches.sort(Comparator.comparingInt((Scored scored) -> -scored.degree())
                .thenComparingInt(scored -> scored.item().title().length())
                .thenComparing(scored -> scored.item().title(), String.CASE_INSENSITIVE_ORDER));
        tableMatches.stream().limit(TABLE_LIMIT).forEach(scored -> results.add(scored.item()));

        for (ConnectionConfig connection : byId.values()) {
            if (!matcher.matches(connection.getName()) && !matcher.matches(connection.getType().getDisplayName())) continue;
            results.add(connectionItem(connection));
            results.add(new Item(Kind.CONSOLE, "Open SQL console on " + connection.getName(), "action", connection, null, null, null, connection.getType()));
        }
        for (DatabaseType type : DatabaseType.values())
            if (matcher.matches(type.getDisplayName())) results.add(newConnectionItem(type));
        return results;
    }

    private static Item connectionItem(ConnectionConfig connection) {
        String tag = connection.getEnvironment().tag();
        return new Item(Kind.CONNECTION, connection.getName(), connection.getType().getDisplayName() + " connection"
                + (tag.isEmpty() ? "" : " · " + tag), connection,
                null, null, null, connection.getType());
    }

    private static Item newConnectionItem(DatabaseType type) {
        return new Item(Kind.NEW_CONNECTION, "New " + type.getDisplayName() + " connection…", "action", null, null, null, null, type);
    }

    private static String tableKey(String connectionId, String database, String name) {
        return connectionId + "\u0000" + database + "\u0000" + name;
    }
}
