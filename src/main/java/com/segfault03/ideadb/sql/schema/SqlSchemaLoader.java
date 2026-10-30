package com.segfault03.ideadb.sql.schema;

import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.service.MetadataService;
import com.segfault03.ideadb.state.MetadataCacheState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Reads schema metadata into the shared cache. Each step takes the shared browser connection on its own, so a large
 * schema never holds it for the whole load and the explorer stays responsive. Runs off the EDT and outside read actions.
 */
final class SqlSchemaLoader {
    /** Columns are read eagerly for this many tables per namespace; the rest wait until something asks for them. */
    static final int EAGER_TABLE_LIMIT = 300;
    /** A refresh re-reads at most this many tables' columns. */
    static final int REFRESH_TABLE_CAP = 1_000;
    /** Runs before each table's columns are read; tests make it throw or wait to simulate a failing or slow read. */
    static volatile Consumer<String> columnReadHook = table -> {};
    private static final Logger LOG = Logger.getInstance(SqlSchemaLoader.class);

    private SqlSchemaLoader() {}

    /**
     * Reads a namespace's tables and their columns. A null namespace means the connection's default, which is
     * resolved and remembered. Tables whose columns are already cached are skipped unless refreshing.
     */
    static void loadNamespace(@NotNull ConnectionConfig config, @Nullable String namespace, boolean refresh, int tableLimit,
                              @NotNull BooleanSupplier cancelled) throws Exception {
        loadNamespace(config, namespace, refresh, tableLimit, cancelled, new ColumnOutcomes() {});
    }

    /**
     * As above. A refresh reads the first {@code tableLimit} tables and every other table whose columns were cached,
     * up to {@link #REFRESH_TABLE_CAP}; cached columns of tables past the cap are dropped so nothing trusts them.
     */
    static void loadNamespace(@NotNull ConnectionConfig config, @Nullable String namespace, boolean refresh, int tableLimit,
                              @NotNull BooleanSupplier cancelled, @NotNull ColumnOutcomes outcomes) throws Exception {
        DatabaseConnectionManager connections = DatabaseConnectionManager.getInstance();
        MetadataService metadata = MetadataService.getInstance();
        MetadataCacheState cache = MetadataCacheState.getInstance();
        var provider = DatabaseProviders.get(config.getType());
        String target = namespace;
        if (target == null) {
            target = connections.executeBrowser(config, connection -> provider.metadata().defaultNamespace(connection, config));
            if (target == null || target.isBlank()) throw new IllegalStateException("The connection reports no default " + provider.namespaceLabel());
            cache.defaultNamespaceResolved(config, target);
        }
        String name = target;
        checkCancelled(cancelled);
        connections.executeBrowser(config, connection -> metadata.getDatabases(connection, config));
        List<TableMetadata> tables = connections.executeBrowser(config, connection -> metadata.getTables(connection, config, name));
        Set<String> cached = tablesWithColumns(cache.snapshot(config), name);
        int reads = 0;
        List<String> dropped = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            String table = tables.get(i).getName();
            boolean eager = i < tableLimit;
            if (refresh ? !eager && !cached.contains(table) : !eager || cached.contains(table)) continue;
            if (refresh && reads >= REFRESH_TABLE_CAP) {
                if (cached.contains(table)) dropped.add(table);
                continue;
            }
            checkCancelled(cancelled);
            reads++;
            readColumns(config, name, table, outcomes);
        }
        if (!dropped.isEmpty()) cache.columnsInvalidated(config, name, dropped);
    }

    /** Re-reads tables after they changed: the table list once (they may be new or gone), then the columns of those that still exist. */
    static void reloadTables(@NotNull ConnectionConfig config, @NotNull String namespace, @NotNull Collection<String> tables,
                             @NotNull BooleanSupplier cancelled, @NotNull ColumnOutcomes outcomes) throws Exception {
        DatabaseConnectionManager connections = DatabaseConnectionManager.getInstance();
        MetadataService metadata = MetadataService.getInstance();
        List<TableMetadata> listed = connections.executeBrowser(config, connection -> metadata.getTables(connection, config, namespace));
        for (String table : tables) {
            checkCancelled(cancelled);
            for (TableMetadata candidate : listed) {
                if (candidate.getName().equalsIgnoreCase(table)) {
                    readColumns(config, namespace, candidate.getName(), outcomes);
                    break;
                }
            }
        }
    }

    static void reloadTable(@NotNull ConnectionConfig config, @NotNull String namespace, @NotNull String table,
                            @NotNull BooleanSupplier cancelled) throws Exception {
        reloadTables(config, namespace, List.of(table), cancelled, new ColumnOutcomes() {});
    }

    /** Hears how each table's column read went, since a failure is not an error of the load as a whole. */
    interface ColumnOutcomes {
        default void failed(String table) {}
        default void read(String table) {}
    }

    private static void readColumns(ConnectionConfig config, String namespace, String table, ColumnOutcomes outcomes) throws Exception {
        try {
            columnReadHook.accept(table);
            DatabaseConnectionManager.getInstance().executeBrowser(config,
                    connection -> MetadataService.getInstance().getColumns(connection, config, namespace, table));
            outcomes.read(table);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            if (failure instanceof ControlFlowException) throw failure;
            // One unreadable table (a permission, a dropped view) must not hide the rest of the schema.
            LOG.info("Could not read columns of " + namespace + "." + table + ": " + failure.getMessage());
            outcomes.failed(table);
        }
    }

    private static Set<String> tablesWithColumns(@Nullable ExplorerMetadataSnapshot snapshot, String namespace) {
        Set<String> names = new HashSet<>();
        if (snapshot == null) return names;
        for (var candidate : snapshot.namespaces()) {
            if (!candidate.name().equals(namespace)) continue;
            for (var table : candidate.tables()) if (table.columnsLoaded()) names.add(table.metadata().getName());
        }
        return names;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException();
    }
}
