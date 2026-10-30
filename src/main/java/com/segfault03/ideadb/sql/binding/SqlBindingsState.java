package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which connection (and so which dialect) applies to SQL in this project. Kept in the workspace file, not VCS,
 * because connection ids are local to a machine. A file's own binding wins over the project default.
 */
@State(name = "LatticeSqlBindings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class SqlBindingsState implements PersistentStateComponent<SqlBindingsState.State> {
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
    }

    private State state = new State();

    public static SqlBindingsState getInstance(@NotNull Project project) { return project.getService(SqlBindingsState.class); }

    @Override public synchronized @NotNull State getState() { return state; }
    @Override public synchronized void loadState(@NotNull State loaded) { state = loaded; }

    public synchronized @Nullable Binding fileBinding(@NotNull VirtualFile file) {
        Binding binding = state.files.get(hostOf(file).getUrl());
        return binding == null ? null : new Binding(binding.connectionId, binding.dialectId);
    }

    public synchronized void bindFileToConnection(@NotNull VirtualFile file, @NotNull String connectionId) {
        state.files.put(hostOf(file).getUrl(), new Binding(connectionId, ""));
    }

    public synchronized void bindFileToDialect(@NotNull VirtualFile file, @NotNull String dialectId) {
        state.files.put(hostOf(file).getUrl(), new Binding("", dialectId));
    }

    public synchronized void clearFile(@NotNull VirtualFile file) { state.files.remove(hostOf(file).getUrl()); }

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
        return changed;
    }

    public synchronized boolean isDiscoveryDismissed(@NotNull String key) { return state.dismissedDiscoveries != null && state.dismissedDiscoveries.contains(key); }
    public synchronized void dismissDiscovery(@NotNull String key) {
        if (state.dismissedDiscoveries == null) state.dismissedDiscoveries = new java.util.ArrayList<>();
        if (!state.dismissedDiscoveries.contains(key)) state.dismissedDiscoveries.add(key);
    }

    public synchronized @NotNull String defaultConnectionId() { return state.defaultConnectionId == null ? "" : state.defaultConnectionId; }
    public synchronized void setDefaultConnectionId(@NotNull String id) { state.defaultConnectionId = id; }
}
