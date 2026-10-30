package com.segfault03.ideadb.sql.schema;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.DatabaseChange;
import com.segfault03.ideadb.service.DatabaseChangeListener;
import com.segfault03.ideadb.service.DatabaseTaskService;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.state.DatabaseSettingsListener;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.MetadataCacheState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * What the editor features know about a connection's schema. {@link #schema} is cheap and safe in a read action:
 * it only reads the cache. {@link #ensureLoaded} starts a background read when the cache is missing or stale and
 * announces the result on {@link SqlSchemaListener}; it never blocks and never touches JDBC on the calling thread.
 *
 * <p>Connection-wide changes (every statement in a console reports one) only mark the schema stale; it is read
 * again the next time something asks for it. Changes to one table re-read just that table.
 */
public final class SqlSchemaService implements Disposable {
    private static final Logger LOG = Logger.getInstance(SqlSchemaService.class);
    private static final long RETRY_AFTER_MILLIS = 30_000;

    private final ConcurrentMap<String, SqlSchema> views = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> loading = new ConcurrentHashMap<>();
    private final Set<String> served = ConcurrentHashMap.newKeySet();
    private final Set<String> stale = ConcurrentHashMap.newKeySet();
    private final FailureBackoff backoff = new FailureBackoff(RETRY_AFTER_MILLIS, System::currentTimeMillis);
    private volatile boolean disposed;

    private interface Work { void run() throws Exception; }

    public SqlSchemaService() {
        var connection = ApplicationManager.getApplication().getMessageBus().connect(this);
        connection.subscribe(DatabaseChangeListener.TOPIC, (DatabaseChangeListener) this::changed);
        connection.subscribe(DatabaseSettingsListener.TOPIC, (DatabaseSettingsListener) this::forgetRemovedConnections);
    }

    public static SqlSchemaService getInstance() { return ApplicationManager.getApplication().getService(SqlSchemaService.class); }

    /** The schema as last cached, or null when nothing has been read for this connection. Reuses the view until the cache changes. */
    public @Nullable SqlSchema schema(@NotNull ConnectionConfig config) {
        MetadataCacheState cache = MetadataCacheState.getInstance();
        long revision = cache.revision(); // read first: a change after this only causes a harmless rebuild next time
        SqlSchema known = views.get(config.getId());
        if (known != null && known.revision() == revision && cache.matches(config)) return known;
        ExplorerMetadataSnapshot snapshot = cache.snapshot(config);
        if (snapshot == null) {
            views.remove(config.getId());
            return null;
        }
        SqlSchema built = SqlSchema.of(config, snapshot, cache.defaultNamespace(config), revision);
        views.put(config.getId(), built);
        return built;
    }

    /** Starts reading the default namespace if it is missing, incomplete or stale. Returns immediately. */
    public void ensureLoaded(@NotNull ConnectionConfig config) {
        if (disposed) return;
        String id = config.getId();
        boolean refresh = stale.remove(id);
        if (!refresh && isReady(schema(config))) {
            served.add(id);
            return;
        }
        if (backoff.blocked(id)) {
            if (refresh) stale.add(id);
            return;
        }
        ConnectionConfig copy = config.copy();
        start(id, id, () -> SqlSchemaLoader.loadNamespace(copy, null, refresh, SqlSchemaLoader.EAGER_TABLE_LIMIT, () -> disposed));
    }

    /** Whether this connection has been read at least once, so changes to it are followed. */
    boolean isServing(@NotNull String connectionId) { return served.contains(connectionId); }

    /** Whether a change to the connection since the last read may have changed its schema, so cached names cannot be trusted. */
    public boolean isStale(@NotNull String connectionId) { return stale.contains(connectionId); }

    /** Whether a read for this connection is running. */
    public boolean isLoading(@NotNull String connectionId) {
        Future<?> running = loading.get(connectionId);
        return running != null && !running.isDone();
    }

    private static boolean isReady(@Nullable SqlSchema schema) {
        if (schema == null || schema.defaultNamespace() == null) return false;
        var namespace = schema.namespace(null);
        return namespace != null && namespace.tablesLoaded()
                && namespace.tables().stream().limit(SqlSchemaLoader.EAGER_TABLE_LIMIT).allMatch(ExplorerMetadataSnapshot.Table::columnsLoaded);
    }

    private void start(String connectionId, String key, Work work) {
        loading.compute(key, (ignored, running) -> {
            if (running != null && !running.isDone()) return running;
            return DatabaseTaskService.getInstance().submit(() -> run(connectionId, key, work));
        });
    }

    private void run(String connectionId, String key, Work work) {
        try {
            work.run();
            served.add(connectionId);
            backoff.succeeded(connectionId);
        } catch (java.util.concurrent.CancellationException cancelled) {
            // The service or the connection went away; nothing to report.
        } catch (Throwable failure) {
            if (!disposed) {
                LOG.info("Could not read the schema of connection " + connectionId + ": " + failure.getMessage());
                backoff.failed(connectionId);
            }
        } finally {
            loading.remove(key);
            if (!disposed) SqlSchemaListener.publish(connectionId);
        }
    }

    private void changed(DatabaseChange change) {
        String id = change.connectionId();
        if (disposed || !served.contains(id)) return; // never connect to a database nobody has used the SQL editor with
        if (change.table() == null) {
            stale.add(id);
            return;
        }
        ConnectionConfig config = DatabaseSettingsState.getInstance().getConnection(id);
        if (config == null) return;
        String namespace = change.namespace(), table = change.table();
        start(id, id + "#" + namespace + "." + table, () -> SqlSchemaLoader.reloadTable(config, namespace, table, () -> disposed));
    }

    private void forgetRemovedConnections() {
        Set<String> existing = DatabaseSettingsState.getInstance().getConnections().stream().map(ConnectionConfig::getId).collect(Collectors.toSet());
        Set<String> known = new HashSet<>(views.keySet());
        known.addAll(served);
        known.addAll(stale);
        for (String key : loading.keySet()) known.add(connectionOf(key));
        for (String id : known) {
            if (existing.contains(id)) continue;
            views.remove(id);
            served.remove(id);
            stale.remove(id);
            backoff.forget(id);
            loading.entrySet().removeIf(entry -> {
                if (!connectionOf(entry.getKey()).equals(id)) return false;
                entry.getValue().cancel(true);
                return true;
            });
        }
    }

    /** Loads are keyed by connection id, or by "id#namespace.table" for a single table. */
    private static String connectionOf(String loadKey) { return loadKey.split("#", 2)[0]; }

    @Override public void dispose() {
        disposed = true;
        loading.values().forEach(running -> running.cancel(true));
        loading.clear();
    }
}
