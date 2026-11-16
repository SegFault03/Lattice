package com.segfault03.ideadb.sql.schema;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;

/**
 * An immutable view of what Lattice knows about one connection's schema, for editor features to read without
 * touching the database. Built from the metadata cache and tied to the cache revision it was built from.
 *
 * <p>Names resolve exactly first, then ignoring case when that identifies a single candidate. A name that
 * differs from two candidates only by case is ambiguous and resolves to nothing.
 *
 * <p>A connection with no default namespace (a MySQL URL that names no database) searches every user namespace for an
 * unqualified name; it resolves only when exactly one namespace holds it.
 */
public final class SqlSchema {
    private final String connectionId;
    private final DatabaseType type;
    private final List<ExplorerMetadataSnapshot.Namespace> namespaces;
    private final String defaultNamespace;
    private final long revision;
    /** Whether unqualified names are looked up in every namespace because the connection has no default one. */
    private final boolean searchesAll;
    /** One name index per namespace, built on first use; the schema is shared between threads. */
    private final AtomicReferenceArray<SqlTableIndex> indexes;

    private SqlSchema(String connectionId, DatabaseType type, List<ExplorerMetadataSnapshot.Namespace> namespaces,
                      String defaultNamespace, boolean searchesAll, long revision) {
        this.connectionId = connectionId;
        this.searchesAll = searchesAll;
        this.type = type;
        this.namespaces = namespaces;
        this.defaultNamespace = defaultNamespace;
        this.revision = revision;
        this.indexes = new AtomicReferenceArray<>(namespaces.size());
    }

    public static @NotNull SqlSchema of(@NotNull ConnectionConfig config, @NotNull ExplorerMetadataSnapshot snapshot,
                                        @Nullable String defaultNamespace, long revision) {
        return of(config, snapshot, defaultNamespace, false, revision);
    }

    /** As above; {@code searchesAll} says the connection has no default namespace, so unqualified names search them all. */
    public static @NotNull SqlSchema of(@NotNull ConnectionConfig config, @NotNull ExplorerMetadataSnapshot snapshot,
                                        @Nullable String defaultNamespace, boolean searchesAll, long revision) {
        return new SqlSchema(config.getId(), config.getType(), List.copyOf(snapshot.namespaces()), defaultNamespace,
                searchesAll && defaultNamespace == null, revision);
    }

    /** Namespaces the server keeps for itself; they are neither read eagerly nor searched for unqualified names. */
    public static boolean isSystemNamespace(@NotNull DatabaseType type, @NotNull String name) {
        if (type != DatabaseType.MYSQL) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("information_schema") || lower.equals("mysql") || lower.equals("performance_schema") || lower.equals("sys");
    }

    /** The same view where unqualified names resolve to another namespace, such as the database a console works in. */
    public @NotNull SqlSchema withDefaultNamespace(@Nullable String name) {
        var named = name == null ? null : namespace(name);
        return named == null || named.name().equals(defaultNamespace) ? this : new SqlSchema(connectionId, type, namespaces, named.name(), searchesAll, revision);
    }

    public @NotNull String connectionId() { return connectionId; }
    public @NotNull DatabaseType type() { return type; }
    /** The cache revision this view was built from. */
    public long revision() { return revision; }
    /** Where unqualified names resolve; null until it has been read from the database. */
    public @Nullable String defaultNamespace() { return defaultNamespace; }
    public @NotNull List<ExplorerMetadataSnapshot.Namespace> namespaces() { return namespaces; }

    /** Whether there is no default namespace and unqualified names are looked up in every user namespace. */
    public boolean searchesAllNamespaces() { return searchesAll && defaultNamespace == null; }

    /** The namespaces an unqualified name is looked up in: the default one, or, with none, every user namespace. */
    public @NotNull List<ExplorerMetadataSnapshot.Namespace> searched() {
        if (!searchesAllNamespaces()) {
            var found = namespace(null);
            return found == null ? List.of() : List.of(found);
        }
        List<ExplorerMetadataSnapshot.Namespace> out = new ArrayList<>();
        for (var candidate : namespaces) if (!isSystemNamespace(type, candidate.name())) out.add(candidate);
        return out;
    }

    /** The namespace that holds this table of the schema, or null when it is not one of them. */
    public @Nullable ExplorerMetadataSnapshot.Namespace namespaceOf(@NotNull ExplorerMetadataSnapshot.Table table) {
        for (var candidate : namespaces) for (var held : candidate.tables()) if (held == table) return candidate;
        return null;
    }

    /** Every searched namespace's table of this name; more than one means an unqualified use of the name is ambiguous. */
    public @NotNull List<ExplorerMetadataSnapshot.Table> unqualifiedMatches(@NotNull String name) {
        List<ExplorerMetadataSnapshot.Table> out = new ArrayList<>();
        for (var candidate : searched()) {
            var found = index(candidate).find(name);
            if (found != null) out.add(found);
        }
        return out;
    }

    /** The named namespace, or the default one for a null name. */
    public @Nullable ExplorerMetadataSnapshot.Namespace namespace(@Nullable String name) {
        String wanted = name != null ? name : defaultNamespace;
        return wanted == null ? null : resolve(namespaces, ExplorerMetadataSnapshot.Namespace::name, wanted);
    }

    public @NotNull List<ExplorerMetadataSnapshot.Table> tables(@Nullable String namespace) {
        if (namespace == null && searchesAllNamespaces()) {
            List<ExplorerMetadataSnapshot.Table> out = new ArrayList<>();
            for (var candidate : searched()) out.addAll(candidate.tables());
            return out;
        }
        var found = namespace(namespace);
        return found == null ? List.of() : found.tables();
    }

    /** A table in the named namespace, or in the default one for a null namespace. */
    public @Nullable ExplorerMetadataSnapshot.Table table(@Nullable String namespace, @NotNull String name) {
        if (namespace == null && searchesAllNamespaces()) {
            var matches = unqualifiedMatches(name);
            return matches.size() == 1 ? matches.get(0) : null;
        }
        var found = namespace(namespace);
        return found == null ? null : index(found).find(name);
    }

    /** The tables of a namespace of this schema by name. Safe to call from several threads. */
    public @NotNull SqlTableIndex index(@NotNull ExplorerMetadataSnapshot.Namespace namespace) {
        for (int i = 0; i < namespaces.size(); i++) {
            if (namespaces.get(i) != namespace) continue;
            SqlTableIndex built = indexes.get(i);
            if (built == null) {
                built = new SqlTableIndex(namespace.tables());
                if (!indexes.compareAndSet(i, null, built)) built = indexes.get(i);
            }
            return built;
        }
        return new SqlTableIndex(namespace.tables());
    }

    /** Whether the namespace's table list is known and every table's columns have been read. */
    public boolean isComplete(@Nullable String namespace) {
        if (namespace == null && searchesAllNamespaces()) {
            var all = searched();
            return !all.isEmpty() && all.stream().allMatch(found -> found.tablesLoaded()
                    && found.tables().stream().allMatch(ExplorerMetadataSnapshot.Table::columnsLoaded));
        }
        var found = namespace(namespace);
        return found != null && found.tablesLoaded() && found.tables().stream().allMatch(ExplorerMetadataSnapshot.Table::columnsLoaded);
    }

    private static <T> @Nullable T resolve(List<T> candidates, Function<T, String> nameOf, String wanted) {
        List<T> ignoringCase = new ArrayList<>();
        for (T candidate : candidates) {
            String name = nameOf.apply(candidate);
            if (wanted.equals(name)) return candidate;
            if (wanted.equalsIgnoreCase(name)) ignoringCase.add(candidate);
        }
        return ignoringCase.size() == 1 ? ignoringCase.get(0) : null;
    }
}
