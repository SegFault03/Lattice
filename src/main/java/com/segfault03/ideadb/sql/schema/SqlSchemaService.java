package com.segfault03.ideadb.sql.schema;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.DatabaseChange;
import com.segfault03.ideadb.service.DatabaseChangeListener;
import com.segfault03.ideadb.service.DatabaseTaskService;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.state.DatabaseSettingsListener;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.MetadataCacheState;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * What the editor features know about a connection's schema. {@link #schema} is cheap and safe in a read action:
 * it only reads the cache. {@link #ensureLoaded} starts a background read when the cache is missing or stale and
 * announces the result on {@link SqlSchemaListener}; it never blocks and never touches JDBC on the calling thread.
 *
 * <p>Connection-wide changes (every statement in a console reports one) only mark the schema stale; it is read
 * again the next time something asks for it. Changes to a few tables re-read just those tables, once per burst.
 */
public final class SqlSchemaService implements Disposable {
    private static final Logger LOG = Logger.getInstance(SqlSchemaService.class);
    private static final long RETRY_AFTER_MILLIS = 30_000;
    private static final long TABLE_DEBOUNCE_MILLIS = 500;
    /** More tables than this changing within one debounce window is handled as a connection-wide change. */
    private static final int TABLE_BURST_LIMIT = 20;

    private final ConcurrentMap<String, SqlSchema> views = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> loading = new ConcurrentHashMap<>();
    private final Set<String> served = ConcurrentHashMap.newKeySet();
    private final Set<String> stale = ConcurrentHashMap.newKeySet();
    private final FailureBackoff backoff = new FailureBackoff(RETRY_AFTER_MILLIS, System::currentTimeMillis);
    /** Counts the connection-wide changes seen, so a refresh can tell whether another arrived while it was reading. */
    private final ConcurrentMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    /** Per connection, the tables whose columns could not be read and when; they count as attempted until the retry interval passes. */
    private final ConcurrentMap<String, ConcurrentMap<String, Long>> failedTables = new ConcurrentHashMap<>();
    /** Table changes waiting out the debounce, per connection. Guarded by itself. */
    private final Map<String, PendingTables> pending = new HashMap<>();
    private final Alarm debounce = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    private volatile boolean disposed;

    private static final class PendingTables {
        final Set<Map.Entry<String, String>> tables = new LinkedHashSet<>();
        boolean overflow;
    }

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
        long revision = cache.revision(config.getId()); // read first: a change after this only causes a harmless rebuild next time
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

    /**
     * Starts reading the default namespace if it is missing, incomplete or stale. Returns immediately. A stale
     * schema stays stale until a read that began after the last change has finished, so a request made while another
     * read is running is answered by the next call, not lost.
     */
    public void ensureLoaded(@NotNull ConnectionConfig config) {
        if (disposed) return;
        String id = config.getId();
        if (!stale.contains(id) && isReady(id, schema(config))) {
            served.add(id);
            return;
        }
        if (backoff.blocked(id)) return;
        ConnectionConfig copy = config.copy();
        start(id, id, () -> {
            long generation = generation(id).get(); // before the stale flag: a change after this leaves the flag set
            boolean refresh = stale.contains(id);
            if (refresh) failedTables.remove(id);
            SqlSchemaLoader.loadNamespace(copy, null, refresh, SqlSchemaLoader.EAGER_TABLE_LIMIT, () -> disposed, outcomes(id));
            if (refresh && generation(id).get() == generation) stale.remove(id);
        });
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

    private boolean isReady(String connectionId, @Nullable SqlSchema schema) {
        if (schema == null || schema.defaultNamespace() == null) return false;
        var namespace = schema.namespace(null);
        if (namespace == null || !namespace.tablesLoaded()) return false;
        // A table whose columns could not be read was attempted; asking again for every completion would never end.
        return namespace.tables().stream().limit(SqlSchemaLoader.EAGER_TABLE_LIMIT)
                .allMatch(table -> table.columnsLoaded() || recentlyFailed(connectionId, table.metadata().getName()));
    }

    private boolean recentlyFailed(String connectionId, String table) {
        var failures = failedTables.get(connectionId);
        Long at = failures == null ? null : failures.get(table);
        return at != null && System.currentTimeMillis() - at < RETRY_AFTER_MILLIS;
    }

    private SqlSchemaLoader.ColumnOutcomes outcomes(String connectionId) {
        return new SqlSchemaLoader.ColumnOutcomes() {
            @Override public void failed(String table) {
                failedTables.computeIfAbsent(connectionId, ignored -> new ConcurrentHashMap<>()).put(table, System.currentTimeMillis());
            }
            @Override public void read(String table) {
                var failures = failedTables.get(connectionId);
                if (failures != null) failures.remove(table);
            }
        };
    }

    private AtomicLong generation(String connectionId) { return generations.computeIfAbsent(connectionId, ignored -> new AtomicLong()); }

    private void markStale(String connectionId) {
        generation(connectionId).incrementAndGet(); // first: a read that sees the flag must also see the new generation
        stale.add(connectionId);
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
        } catch (Exception failure) {
            if (failure instanceof ControlFlowException) throw (RuntimeException) failure;
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
            markStale(id);
            return;
        }
        synchronized (pending) {
            boolean first = !pending.containsKey(id);
            PendingTables tables = pending.computeIfAbsent(id, ignored -> new PendingTables());
            if (!tables.overflow) {
                tables.tables.add(Map.entry(change.namespace(), change.table()));
                if (tables.tables.size() > TABLE_BURST_LIMIT) {
                    tables.overflow = true;
                    tables.tables.clear();
                }
            }
            if (first) debounce.addRequest(() -> flushTableChanges(id), TABLE_DEBOUNCE_MILLIS);
        }
    }

    /** A burst of table changes (a DDL script) becomes one read; a very large burst is treated as a connection-wide change. */
    private void flushTableChanges(String id) {
        if (disposed) return;
        String key = id + "#tables";
        Future<?> running = loading.get(key);
        PendingTables batch;
        synchronized (pending) {
            if (running != null && !running.isDone()) {
                debounce.addRequest(() -> flushTableChanges(id), TABLE_DEBOUNCE_MILLIS); // keep the changes for the next round
                return;
            }
            batch = pending.remove(id);
        }
        if (batch == null) return;
        if (batch.overflow) {
            markStale(id);
            SqlSchemaListener.publish(id);
            return;
        }
        ConnectionConfig config = DatabaseSettingsState.getInstance().getConnection(id);
        if (config == null) return;
        Map<String, List<String>> byNamespace = new LinkedHashMap<>();
        for (var entry : batch.tables) byNamespace.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue());
        start(id, key, () -> {
            for (var namespace : byNamespace.entrySet()) {
                SqlSchemaLoader.reloadTables(config, namespace.getKey(), namespace.getValue(), () -> disposed, outcomes(id));
            }
        });
    }

    private void forgetRemovedConnections() {
        Set<String> existing = DatabaseSettingsState.getInstance().getConnections().stream().map(ConnectionConfig::getId).collect(Collectors.toSet());
        Set<String> known = new HashSet<>(views.keySet());
        known.addAll(served);
        known.addAll(stale);
        known.addAll(failedTables.keySet());
        synchronized (pending) { known.addAll(pending.keySet()); }
        for (String key : loading.keySet()) known.add(connectionOf(key));
        for (String id : known) {
            if (existing.contains(id)) continue;
            views.remove(id);
            served.remove(id);
            stale.remove(id);
            generations.remove(id);
            failedTables.remove(id);
            synchronized (pending) { pending.remove(id); }
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
