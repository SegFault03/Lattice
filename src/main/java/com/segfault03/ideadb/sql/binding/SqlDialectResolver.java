package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Finds the dialect for a file. A file's own binding wins (a connection, or an explicit dialect such as generic SQL),
 * then the project's default connection, then generic SQL. A binding to a deleted connection falls through.
 */
public final class SqlDialectResolver {
    private SqlDialectResolver() {}

    public static @NotNull SqlDialectProfile resolve(@Nullable Project project, @Nullable VirtualFile file) {
        if (project == null || project.isDisposed()) return SqlDialects.ANSI;
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        SqlBindingsState.Binding binding = file == null ? null : bindings.fileBinding(file);
        if (binding != null) {
            ConnectionConfig bound = connection(binding.connectionId);
            if (bound != null) return SqlDialects.of(bound.getType());
            if (!binding.dialectId.isEmpty()) return SqlDialects.byId(binding.dialectId);
        }
        ConnectionConfig fallback = connection(bindings.defaultConnectionId());
        return fallback == null ? SqlDialects.ANSI : SqlDialects.of(fallback.getType());
    }

    /** The connection that applies to a file, from its binding or else the project default; null when none does. */
    public static @Nullable ConnectionConfig connection(@NotNull Project project, @Nullable VirtualFile file) {
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        SqlBindingsState.Binding binding = file == null ? null : bindings.fileBinding(file);
        if (binding != null) {
            ConnectionConfig bound = connection(binding.connectionId);
            if (bound != null) return bound;
            if (!binding.dialectId.isEmpty()) return null;
        }
        return connection(bindings.defaultConnectionId());
    }

    private static @Nullable ConnectionConfig connection(@Nullable String id) {
        return id == null || id.isEmpty() ? null : DatabaseSettingsState.getInstance().getConnection(id);
    }
}
