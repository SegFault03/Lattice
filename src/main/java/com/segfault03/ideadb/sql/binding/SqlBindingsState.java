package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which connection (and so which dialect) applies to SQL in this project. Kept in the workspace file, not VCS,
 * because connection ids are local to a machine. A file's own binding wins over the project default. Bindings follow
 * a file that is renamed or moved (and the files under a directory that is), and are dropped when it is deleted.
 * The modification count changes with every binding, so results that depend on them can be cached against it.
 */
@State(name = "LatticeSqlBindings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class SqlBindingsState implements PersistentStateComponent<SqlBindingsState.State>, ModificationTracker, Disposable {
    public static class Binding {
        /** A Lattice connection id, or empty. */
        public String connectionId = "";
        /** An explicit dialect id (for example the generic one), used when no connection is bound. */
        public String dialectId = "";
        public Binding() {}
        Binding(String connectionId, String dialectId) { this.connectionId = connectionId; this.dialectId = dialectId; }
    }

    public static class State {
        public String defaultConnectionId = "";
        public Map<String, Binding> files = new LinkedHashMap<>();
        /** Databases found in project files that the user chose not to be offered again, by their description. */
        public java.util.List<String> dismissedDiscoveries = new java.util.ArrayList<>();
        /** Whether the project's configuration files are read to find its database. */
        public boolean useProjectFiles = true;
    }

    private State state = new State();
    private final AtomicLong modifications = new AtomicLong();

    public SqlBindingsState(@NotNull Project project) {
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(@NotNull List<? extends VFileEvent> events) { followFiles(events); }
        });
    }

    public static SqlBindingsState getInstance(@NotNull Project project) { return project.getService(SqlBindingsState.class); }

    @Override public void dispose() {}

    @Override public long getModificationCount() { return modifications.get(); }

    /** Marks a change that is not a binding but can change what a binding resolves to, such as a connection that was edited. */
    public void connectionsChanged() { modifications.incrementAndGet(); }

    @Override public synchronized @NotNull State getState() { return state; }
    @Override public synchronized void loadState(@NotNull State loaded) {
        state = loaded;
        modifications.incrementAndGet();
    }

    public synchronized @Nullable Binding fileBinding(@NotNull VirtualFile file) {
        Binding binding = state.files.get(hostOf(file).getUrl());
        return binding == null ? null : new Binding(binding.connectionId, binding.dialectId);
    }

    public synchronized void bindFileToConnection(@NotNull VirtualFile file, @NotNull String connectionId) {
        state.files.put(hostOf(file).getUrl(), new Binding(connectionId, ""));
        modifications.incrementAndGet();
    }

    public synchronized void bindFileToDialect(@NotNull VirtualFile file, @NotNull String dialectId) {
        state.files.put(hostOf(file).getUrl(), new Binding("", dialectId));
        modifications.incrementAndGet();
    }

    public synchronized void clearFile(@NotNull VirtualFile file) {
        state.files.remove(hostOf(file).getUrl());
        modifications.incrementAndGet();
    }

    private void followFiles(List<? extends VFileEvent> events) {
        for (VFileEvent event : events) {
            if (event instanceof VFilePropertyChangeEvent change) {
                if (change.getPropertyName().equals(VirtualFile.PROP_NAME)) moveKeys(urlOf(change, change.getOldPath()), urlOf(change, change.getNewPath()));
            } else if (event instanceof VFileMoveEvent move) {
                moveKeys(urlOf(move, move.getOldPath()), urlOf(move, move.getNewPath()));
            } else if (event instanceof VFileDeleteEvent delete) {
                dropKeys(urlOf(delete, delete.getPath()));
            }
        }
    }

    private static String urlOf(VFileEvent event, String path) {
        return VirtualFileManager.constructUrl(event.getFileSystem().getProtocol(), path);
    }

    private static boolean isAtOrUnder(String key, String url) { return key.equals(url) || key.startsWith(url.endsWith("/") ? url : url + "/"); }

    /** Rewrites the keys of a file, or of every file under a directory, from its old URL to its new one. */
    synchronized void moveKeys(@NotNull String oldUrl, @NotNull String newUrl) {
        if (oldUrl.equals(newUrl)) return;
        Map<String, Binding> moved = new LinkedHashMap<>();
        for (var entry : state.files.entrySet()) {
            if (isAtOrUnder(entry.getKey(), oldUrl)) moved.put(newUrl + entry.getKey().substring(oldUrl.length()), entry.getValue());
        }
        if (moved.isEmpty()) return;
        state.files.keySet().removeIf(key -> isAtOrUnder(key, oldUrl));
        state.files.putAll(moved);
        modifications.incrementAndGet();
    }

    /** Forgets the binding of a deleted file, or of every file under a deleted directory. */
    synchronized void dropKeys(@NotNull String url) {
        if (state.files.keySet().removeIf(key -> isAtOrUnder(key, url))) modifications.incrementAndGet();
    }

    /**
     * Forgets bindings of local files that no longer exist, for files deleted while the project was closed. Touches the
     * disk, so it does not belong on the UI thread. Returns whether anything changed.
     */
    public boolean pruneMissingFiles() {
        List<String> keys;
        synchronized (this) { keys = new ArrayList<>(state.files.keySet()); }
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!key.startsWith("file://")) continue;
            try {
                if (!Files.exists(Path.of(VirtualFileManager.extractPath(key)))) missing.add(key);
            } catch (InvalidPathException unreadable) {
                // a key that is no path on this machine is left alone
            }
        }
        if (missing.isEmpty()) return false;
        synchronized (this) {
            boolean changed = state.files.keySet().removeAll(missing);
            if (changed) modifications.incrementAndGet();
            return changed;
        }
    }

    /** SQL injected into a source file is bound through that file. */
    public static @NotNull VirtualFile hostOf(@NotNull VirtualFile file) {
        return file instanceof com.intellij.injected.editor.VirtualFileWindow window ? window.getDelegate() : file;
    }

    /**
     * Forgets bindings to connections that were deleted: a file bound to one goes back to being unbound, and a
     * project default pointing at one is cleared. Explicit dialect choices are kept. Returns whether anything changed.
     */
    public synchronized boolean pruneConnections(@NotNull java.util.Set<String> existing) {
        boolean changed = state.files.values().removeIf(binding -> !binding.connectionId.isEmpty() && !existing.contains(binding.connectionId));
        String chosen = defaultConnectionId();
        if (!chosen.isEmpty() && !existing.contains(chosen)) {
            state.defaultConnectionId = "";
            changed = true;
        }
        if (changed) modifications.incrementAndGet();
        return changed;
    }

    public synchronized boolean isDiscoveryDismissed(@NotNull String key) { return state.dismissedDiscoveries != null && state.dismissedDiscoveries.contains(key); }
    public synchronized void dismissDiscovery(@NotNull String key) {
        if (state.dismissedDiscoveries == null) state.dismissedDiscoveries = new java.util.ArrayList<>();
        if (!state.dismissedDiscoveries.contains(key)) state.dismissedDiscoveries.add(key);
    }

    public synchronized void clearDismissedDiscoveries() { if (state.dismissedDiscoveries != null) state.dismissedDiscoveries.clear(); }
    public synchronized boolean useProjectFiles() { return state.useProjectFiles; }
    public synchronized void useProjectFiles(boolean value) { state.useProjectFiles = value; }

    public synchronized @NotNull String defaultConnectionId() { return state.defaultConnectionId == null ? "" : state.defaultConnectionId; }
    public synchronized void setDefaultConnectionId(@NotNull String id) {
        state.defaultConnectionId = id;
        modifications.incrementAndGet();
    }
}
