package com.segfault03.ideadb.state;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Last successful metadata reads, local to this IDE. Failed reads never replace cached metadata. */
@State(name = "LatticeMetadataCache", storages = @Storage(value = "LatticeMetadata.xml", roamingType = RoamingType.DISABLED))
public final class MetadataCacheState implements PersistentStateComponent<MetadataCacheState.State> {
    public static final class State { public Map<String, Source> sources = new LinkedHashMap<>(); }
    public static final class Source {
        public String identity = "";
        /** The namespace unqualified names resolve to on this connection; empty until it has been read. */
        public String defaultNamespace = "";
        public Map<String, Namespace> namespaces = new LinkedHashMap<>();
    }
    public static final class Namespace {
        public boolean tablesLoaded;
        public Map<String, Table> tables = new LinkedHashMap<>();
    }
    public static final class Table {
        public String catalog, schema, name, type, comment;
        public boolean columnsLoaded;
        public List<Column> columns = new ArrayList<>();
        TableMetadata metadata() {
            var metadata = new TableMetadata(catalog, schema, name, type, comment);
            metadata.setColumns(columns.stream().map(Column::metadata).toList());
            return metadata;
        }
    }
    public static final class Column {
        public String name, typeName, defaultValue, comment, refNamespace, refTable, refColumn;
        public int dataType, columnSize, decimalDigits;
        public boolean nullable, primaryKey, foreignKey, autoIncrement, generated;
        static Column capture(ColumnMetadata metadata) {
            var column = new Column();
            column.name = metadata.getName(); column.typeName = metadata.getTypeName();
            column.dataType = metadata.getDataType(); column.columnSize = metadata.getColumnSize();
            column.decimalDigits = metadata.getDecimalDigits(); column.defaultValue = metadata.getDefaultValue();
            column.nullable = metadata.isNullable(); column.primaryKey = metadata.isPrimaryKey();
            column.foreignKey = metadata.isForeignKey(); column.autoIncrement = metadata.isAutoIncrement();
            column.generated = metadata.isGenerated();
            column.comment = metadata.getComment();
            var reference = metadata.getReference();
            if (reference != null) { column.refNamespace = reference.namespace(); column.refTable = reference.table(); column.refColumn = reference.column(); }
            return column;
        }
        ColumnMetadata metadata() {
            // Older caches have a foreign-key flag but no target until the next refresh reads one.
            return new ColumnMetadata(name, typeName, dataType, columnSize, decimalDigits, nullable,
                    primaryKey, autoIncrement, defaultValue, generated).withForeignKey(foreignKey).withComment(comment)
                    .withReference(refTable == null ? null : new ColumnReference(refNamespace, refTable, refColumn));
        }
    }

    private State state = new State();
    /** Bumped on every change, so consumers can tell when a derived view of the cache is stale without copying it. */
    private long revision;
    /** The same, per connection, so a change to one connection does not invalidate what was derived from another. */
    private final Map<String, Long> revisions = new HashMap<>();
    private static final class Standalone { private static final MetadataCacheState INSTANCE = new MetadataCacheState(); }
    public static MetadataCacheState getInstance() {
        var application = ApplicationManager.getApplication();
        return application == null ? Standalone.INSTANCE : application.getService(MetadataCacheState.class);
    }

    // Never persist a JDBC URL (which may contain credentials) or the connection configuration.
    private static String identity(ConnectionConfig config) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(config.getType().name(), config.buildJdbcUrl(), Objects.requireNonNullElse(config.getUser(), ""))) {
                digest.update(part.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Source source(ConnectionConfig config) {
        String identity = identity(config);
        var source = state.sources.computeIfAbsent(config.getId(), ignored -> new Source());
        if (!identity.equals(source.identity)) {
            source = new Source(); source.identity = identity;
            state.sources.put(config.getId(), source);
        }
        return source;
    }

    public synchronized long revision() { return revision; }

    /** Bumped by every change to this connection's cached metadata, and by nothing else. */
    public synchronized long revision(String connectionId) { return revisions.getOrDefault(connectionId, 0L); }

    private void changed(String connectionId) {
        revision++;
        revisions.merge(connectionId, 1L, Long::sum);
    }

    public synchronized void defaultNamespaceResolved(ConnectionConfig config, String namespace) {
        if (namespace == null) return;
        var source = source(config);
        if (namespace.equals(source.defaultNamespace)) return;
        changed(config.getId());
        source.defaultNamespace = namespace;
    }

    /** Whether the cache holds metadata read for this exact connection (same engine, URL and user). */
    public synchronized boolean matches(ConnectionConfig config) {
        var source = state.sources.get(config.getId());
        return source != null && source.identity.equals(identity(config));
    }

    /** The cached default namespace, or null when the connection has not been read or its identity changed. */
    public synchronized String defaultNamespace(ConnectionConfig config) {
        var source = state.sources.get(config.getId());
        if (source == null || !source.identity.equals(identity(config)) || source.defaultNamespace == null || source.defaultNamespace.isEmpty()) return null;
        return source.defaultNamespace;
    }

    public synchronized void databasesRefreshed(ConnectionConfig config, List<String> names) {
        changed(config.getId());
        var source = source(config);
        source.namespaces.keySet().retainAll(names);
        for (String name : names) source.namespaces.computeIfAbsent(name, ignored -> new Namespace());
    }

    public synchronized void tablesRefreshed(ConnectionConfig config, String namespace, List<TableMetadata> metadata) {
        if (namespace == null) return;
        changed(config.getId());
        var cached = source(config).namespaces.computeIfAbsent(namespace, ignored -> new Namespace());
        var tables = new LinkedHashMap<String, Table>();
        for (var table : metadata) {
            var entry = cached.tables.get(table.getName());
            if (entry == null || !Objects.equals(entry.type, table.getType())) entry = new Table();
            entry.catalog = table.getCatalog(); entry.schema = table.getSchema();
            entry.name = table.getName(); entry.type = table.getType(); entry.comment = table.getComment();
            tables.put(entry.name, entry);
        }
        cached.tables = tables;
        cached.tablesLoaded = true;
    }

    public synchronized void columnsRefreshed(ConnectionConfig config, String namespace, String table, List<ColumnMetadata> columns) {
        if (namespace == null) return;
        changed(config.getId());
        var cached = source(config).namespaces.computeIfAbsent(namespace, ignored -> new Namespace());
        var entry = cached.tables.computeIfAbsent(table, ignored -> {
            var value = new Table(); value.name = table; value.type = "TABLE"; return value;
        });
        entry.columns = columns.stream().map(Column::capture).toList();
        entry.columnsLoaded = true;
    }

    /** Marks these tables' columns as not read, so nothing trusts columns that could no longer be refreshed. */
    public synchronized void columnsInvalidated(ConnectionConfig config, String namespace, Collection<String> tables) {
        var cached = namespace == null ? null : source(config).namespaces.get(namespace);
        if (cached == null) return;
        boolean any = false;
        for (String name : tables) {
            var entry = cached.tables.get(name);
            if (entry == null || !entry.columnsLoaded) continue;
            entry.columnsLoaded = false;
            any = true;
        }
        if (any) changed(config.getId());
    }

    public synchronized ExplorerMetadataSnapshot snapshot(ConnectionConfig config) {
        var source = state.sources.get(config.getId());
        if (source == null || !source.identity.equals(identity(config))) return null;
        var namespaces = new ArrayList<ExplorerMetadataSnapshot.Namespace>();
        source.namespaces.forEach((name, namespace) -> namespaces.add(new ExplorerMetadataSnapshot.Namespace(name,
                namespace.tables.values().stream().map(table -> {
                    var metadata = table.metadata();
                    return new ExplorerMetadataSnapshot.Table(metadata, List.copyOf(metadata.getColumns()), table.columnsLoaded);
                }).toList(), namespace.tablesLoaded)));
        return new ExplorerMetadataSnapshot(List.copyOf(namespaces), List.of(), 3);
    }

    public synchronized void remove(String connectionId) { changed(connectionId); state.sources.remove(connectionId); }

    @Override public synchronized State getState() { return copy(state); }
    @Override public synchronized void loadState(State loaded) {
        revision++;
        for (String id : state.sources.keySet()) revisions.merge(id, 1L, Long::sum);
        for (String id : loaded.sources.keySet()) revisions.merge(id, 1L, Long::sum);
        state = copy(loaded);
    }
    private static State copy(State original) {
        var copy = new State();
        original.sources.forEach((id, source) -> {
            var sourceCopy = new Source(); sourceCopy.identity = source.identity; sourceCopy.defaultNamespace = source.defaultNamespace;
            source.namespaces.forEach((name, namespace) -> {
                var namespaceCopy = new Namespace(); namespaceCopy.tablesLoaded = namespace.tablesLoaded;
                namespace.tables.forEach((tableName, table) -> {
                    var tableCopy = new Table();
                    tableCopy.catalog = table.catalog; tableCopy.schema = table.schema;
                    tableCopy.name = table.name; tableCopy.type = table.type; tableCopy.comment = table.comment;
                    tableCopy.columnsLoaded = table.columnsLoaded;
                    tableCopy.columns = table.columns.stream().map(column -> Column.capture(column.metadata())).toList();
                    namespaceCopy.tables.put(tableName, tableCopy);
                });
                sourceCopy.namespaces.put(name, namespaceCopy);
            });
            copy.sources.put(id, sourceCopy);
        });
        return copy;
    }
}
