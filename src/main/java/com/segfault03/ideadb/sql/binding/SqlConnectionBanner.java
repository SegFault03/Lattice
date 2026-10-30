package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.function.Function;

/** Offers to pick a connection when a SQL file has none, so it is highlighted for the right database. */
public final class SqlConnectionBanner implements EditorNotificationProvider {
    @Override
    public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(@NotNull Project project, @NotNull VirtualFile file) {
        if (file.getFileType() != LatticeSqlFileType.INSTANCE) return null;
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        if (bindings.fileBinding(file) != null || !bindings.defaultConnectionId().isEmpty()) return null;
        if (DatabaseSettingsState.getInstance().getConnections().isEmpty()) return null;
        return editor -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
            panel.setText("No Lattice connection selected. This SQL is treated as generic SQL.");
            panel.createActionLabel("Choose connection", () -> SqlConnectionChooser.chooseForFile(project, file));
            panel.createActionLabel("Use for project", () -> SqlConnectionChooser.chooseForProject(project));
            panel.createActionLabel("Keep generic", () -> {
                SqlBindingsState.getInstance(project).bindFileToDialect(file, com.segfault03.ideadb.sql.dialect.SqlDialects.ANSI.id());
                SqlEditorRefresh.refresh(project, file);
            });
            return panel;
        };
    }
}
