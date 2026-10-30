package com.segfault03.ideadb.sql;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Parent for the SQL editor's listeners on a project. Unlike the project itself it belongs to the plugin, so
 * subscriptions made against it end when the plugin is unloaded, not when the project closes.
 */
@Service(Service.Level.PROJECT)
public final class SqlEditorProjectScope implements Disposable {
    public static @NotNull Disposable of(@NotNull Project project) { return project.getService(SqlEditorProjectScope.class); }

    @Override public void dispose() {}
}
