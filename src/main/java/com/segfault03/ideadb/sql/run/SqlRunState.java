package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What running SQL remembers in this project: the parameter values last entered for each file and parameter key, so
 * the values dialog opens pre-filled, and whether runs that roll back ask for confirmation. Kept in the workspace file
 * because values are local to a person. Files are keyed by the URL of the host file, as bindings are.
 */
@State(name = "LatticeSqlRun", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class SqlRunState implements PersistentStateComponent<SqlRunState.State> {
    public static class StoredValue {
        /** A {@link SqlParameterValue.Type} name. */
        public String type = "";
        public String text = "";
        public StoredValue() {}
        StoredValue(String type, String text) { this.type = type; this.text = text; }
    }

    public static class State {
        public Map<String, Map<String, StoredValue>> files = new LinkedHashMap<>();
        public boolean confirmRollbackRuns = true;
    }

    private State state = new State();

    public static SqlRunState getInstance(@NotNull Project project) { return project.getService(SqlRunState.class); }

    @Override public synchronized @NotNull State getState() { return state; }
    @Override public synchronized void loadState(@NotNull State loaded) { state = loaded; }

    /** A copy of the values last entered for the file; stored values of an unknown type are left out. */
    public synchronized @NotNull Map<String, SqlParameterValue> values(@NotNull String fileUrl) {
        Map<String, SqlParameterValue> result = new LinkedHashMap<>();
        Map<String, StoredValue> stored = state.files.get(fileUrl);
        if (stored == null) return result;
        for (var entry : stored.entrySet()) {
            StoredValue value = entry.getValue();
            if (value == null || value.type == null || value.text == null) continue;
            try {
                result.put(entry.getKey(), new SqlParameterValue(SqlParameterValue.Type.valueOf(value.type), value.text));
            } catch (IllegalArgumentException unknownType) {
                // a type from another version is skipped
            }
        }
        return result;
    }

    /** Merges the values into those already kept for the file; keys missing from {@code values} stay. */
    public synchronized void remember(@NotNull String fileUrl, @NotNull Map<String, SqlParameterValue> values) {
        if (values.isEmpty()) return;
        Map<String, StoredValue> stored = state.files.computeIfAbsent(fileUrl, key -> new LinkedHashMap<>());
        for (var entry : values.entrySet()) {
            stored.put(entry.getKey(), new StoredValue(entry.getValue().type().name(), entry.getValue().text()));
        }
    }

    public synchronized boolean confirmRollbackRuns() { return state.confirmRollbackRuns; }
    public synchronized void setConfirmRollbackRuns(boolean value) { state.confirmRollbackRuns = value; }
}
