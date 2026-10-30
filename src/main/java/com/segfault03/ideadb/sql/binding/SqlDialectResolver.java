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
 * then the project's default connection, then the saved connection that the project's own configuration files point
 * at, then the only connection there is, then the database type those files agree on, then generic SQL. A binding to
 * a deleted connection falls through.
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
        ConnectionConfig fallback = projectLevel(project, bindings);
        if (fallback != null) return SqlDialects.of(fallback.getType());
        var implied = com.segfault03.ideadb.sql.discovery.JdbcDiscovery.getInstance(project).impliedType();
        return implied == null ? SqlDialects.ANSI : SqlDialects.of(implied);
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
        return projectLevel(project, bindings);
    }

    /** The connection that applies to files with no choice of their own: the project default, or what its files or sole connection give; null when none does. */
    public static @Nullable ConnectionConfig projectConnection(@NotNull Project project) {
        return projectLevel(project, SqlBindingsState.getInstance(project));
    }

    /** What files with no choice of their own resolve to, for a person: a connection's name, a database type, or generic SQL. */
    public static @NotNull String describeProjectDefault(@NotNull Project project) {
        ConnectionConfig connection = projectConnection(project);
        if (connection != null) return connection.getName();
        return resolve(project, null).displayName();
    }

    /** Whether the file's connection comes from what the project's configuration files say, rather than from a choice. */
    public static boolean isFromProjectFiles(@NotNull Project project, @Nullable VirtualFile file) {
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        SqlBindingsState.Binding binding = file == null ? null : bindings.fileBinding(file);
        if (binding != null && (connection(binding.connectionId) != null || !binding.dialectId.isEmpty())) return false;
        return connection(bindings.defaultConnectionId()) == null && matchedByProjectFiles(project) != null;
    }

    /** The project's default connection, then the one its files point at, then the single saved connection, which needs no choosing. */
    private static @Nullable ConnectionConfig projectLevel(Project project, SqlBindingsState bindings) {
        ConnectionConfig chosen = connection(bindings.defaultConnectionId());
        if (chosen != null) return chosen;
        ConnectionConfig discovered = matchedByProjectFiles(project);
        if (discovered != null) return discovered;
        var settings = DatabaseSettingsState.getInstance();
        if (settings.connectionCount() != 1) return null;
        var only = settings.getConnections();
        return only.size() == 1 ? only.get(0) : null;
    }

    private static @Nullable ConnectionConfig matchedByProjectFiles(Project project) {
        return com.segfault03.ideadb.sql.discovery.JdbcDiscovery.getInstance(project).matchedConnection();
    }

    private static @Nullable ConnectionConfig connection(@Nullable String id) {
        return id == null || id.isEmpty() ? null : DatabaseSettingsState.getInstance().getConnection(id);
    }
}
