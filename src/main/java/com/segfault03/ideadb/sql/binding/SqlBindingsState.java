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
    }

    private State state = new State();

    public static SqlBindingsState getInstance(@NotNull Project project) { return project.getService(SqlBindingsState.class); }

    @Override public synchronized @NotNull State getState() { return state; }
    @Override public synchronized void loadState(@NotNull State loaded) { state = loaded; }

    public synchronized @Nullable Binding fileBinding(@NotNull VirtualFile file) {
        Binding binding = state.files.get(file.getUrl());
        return binding == null ? null : new Binding(binding.connectionId, binding.dialectId);
    }

    public synchronized void bindFileToConnection(@NotNull VirtualFile file, @NotNull String connectionId) {
        state.files.put(file.getUrl(), new Binding(connectionId, ""));
    }

    public synchronized void bindFileToDialect(@NotNull VirtualFile file, @NotNull String dialectId) {
        state.files.put(file.getUrl(), new Binding("", dialectId));
    }

    public synchronized void clearFile(@NotNull VirtualFile file) { state.files.remove(file.getUrl()); }

    public synchronized @NotNull String defaultConnectionId() { return state.defaultConnectionId == null ? "" : state.defaultConnectionId; }
    public synchronized void setDefaultConnectionId(@NotNull String id) { state.defaultConnectionId = id; }
}
