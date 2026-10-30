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
 */
public final class SqlSchema {
    private final String connectionId;
    private final DatabaseType type;
    private final List<ExplorerMetadataSnapshot.Namespace> namespaces;
    private final String defaultNamespace;
    private final long revision;
    /** One name index per namespace, built on first use; the schema is shared between threads. */
    private final AtomicReferenceArray<SqlTableIndex> indexes;

    private SqlSchema(String connectionId, DatabaseType type, List<ExplorerMetadataSnapshot.Namespace> namespaces,
                      String defaultNamespace, long revision) {
        this.connectionId = connectionId;
        this.type = type;
        this.namespaces = namespaces;
        this.defaultNamespace = defaultNamespace;
        this.revision = revision;
        this.indexes = new AtomicReferenceArray<>(namespaces.size());
    }

    public static @NotNull SqlSchema of(@NotNull ConnectionConfig config, @NotNull ExplorerMetadataSnapshot snapshot,
                                        @Nullable String defaultNamespace, long revision) {
        return new SqlSchema(config.getId(), config.getType(), List.copyOf(snapshot.namespaces()), defaultNamespace, revision);
    }

    public @NotNull String connectionId() { return connectionId; }
    public @NotNull DatabaseType type() { return type; }
    /** The cache revision this view was built from. */
    public long revision() { return revision; }
    /** Where unqualified names resolve; null until it has been read from the database. */
    public @Nullable String defaultNamespace() { return defaultNamespace; }
    public @NotNull List<ExplorerMetadataSnapshot.Namespace> namespaces() { return namespaces; }

    /** The named namespace, or the default one for a null name. */
    public @Nullable ExplorerMetadataSnapshot.Namespace namespace(@Nullable String name) {
        String wanted = name != null ? name : defaultNamespace;
        return wanted == null ? null : resolve(namespaces, ExplorerMetadataSnapshot.Namespace::name, wanted);
    }

    public @NotNull List<ExplorerMetadataSnapshot.Table> tables(@Nullable String namespace) {
        var found = namespace(namespace);
        return found == null ? List.of() : found.tables();
    }

    /** A table in the named namespace, or in the default one for a null namespace. */
    public @Nullable ExplorerMetadataSnapshot.Table table(@Nullable String namespace, @NotNull String name) {
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
