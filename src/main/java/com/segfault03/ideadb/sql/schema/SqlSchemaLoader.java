package com.segfault03.ideadb.sql.schema;

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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Reads schema metadata into the shared cache. Each step takes the shared browser connection on its own, so a large
 * schema never holds it for the whole load and the explorer stays responsive. Runs off the EDT and outside read actions.
 */
final class SqlSchemaLoader {
    /** Columns are read eagerly for this many tables per namespace; the rest wait until something asks for them. */
    static final int EAGER_TABLE_LIMIT = 300;
    private static final Logger LOG = Logger.getInstance(SqlSchemaLoader.class);

    private SqlSchemaLoader() {}

    /**
     * Reads a namespace's tables and their columns. A null namespace means the connection's default, which is
     * resolved and remembered. Tables whose columns are already cached are skipped unless refreshing.
     */
    static void loadNamespace(@NotNull ConnectionConfig config, @Nullable String namespace, boolean refresh, int tableLimit,
                              @NotNull BooleanSupplier cancelled) throws Exception {
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
        Set<String> alreadyRead = refresh ? Set.of() : tablesWithColumns(cache.snapshot(config), name);
        for (TableMetadata table : tables.subList(0, Math.min(tables.size(), tableLimit))) {
            checkCancelled(cancelled);
            if (alreadyRead.contains(table.getName())) continue;
            readColumns(config, name, table.getName());
        }
    }

    /** Re-reads one table after it changed: the table list (it may be new or gone), then its columns if it still exists. */
    static void reloadTable(@NotNull ConnectionConfig config, @NotNull String namespace, @NotNull String table,
                            @NotNull BooleanSupplier cancelled) throws Exception {
        DatabaseConnectionManager connections = DatabaseConnectionManager.getInstance();
        MetadataService metadata = MetadataService.getInstance();
        List<TableMetadata> tables = connections.executeBrowser(config, connection -> metadata.getTables(connection, config, namespace));
        checkCancelled(cancelled);
        for (TableMetadata candidate : tables) {
            if (candidate.getName().equalsIgnoreCase(table)) {
                readColumns(config, namespace, candidate.getName());
                return;
            }
        }
    }

    private static void readColumns(ConnectionConfig config, String namespace, String table) throws Exception {
        try {
            DatabaseConnectionManager.getInstance().executeBrowser(config,
                    connection -> MetadataService.getInstance().getColumns(connection, config, namespace, table));
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            // One unreadable table (a permission, a dropped view) must not hide the rest of the schema.
            LOG.info("Could not read columns of " + namespace + "." + table + ": " + failure.getMessage());
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
