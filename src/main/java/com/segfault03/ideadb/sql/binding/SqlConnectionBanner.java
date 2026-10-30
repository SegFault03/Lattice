package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.discovery.DiscoveredConnection;
import com.segfault03.ideadb.sql.discovery.ProjectConnectionOffer;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.function.Function;

/**
 * Offers a connection for a SQL file that has none. If the project's files point at a database that is not saved yet,
 * the offer is to add it; otherwise, with several saved connections to choose from, it is to pick one.
 */
public final class SqlConnectionBanner implements EditorNotificationProvider {
    @Override
    public @Nullable Function<? super FileEditor, ? extends JComponent> collectNotificationData(@NotNull Project project, @NotNull VirtualFile file) {
        if (file.getFileType() != LatticeSqlFileType.INSTANCE) return null;
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        if (bindings.fileBinding(file) != null || !bindings.defaultConnectionId().isEmpty()) return null;
        // A saved connection that the project's own files point at is used without being chosen.
        if (SqlDialectResolver.isFromProjectFiles(project, file)) return null;
        var found = ProjectConnectionOffer.open(project);
        if (!found.isEmpty()) return foundInProject(project, file, found.get(0));
        // With no connection there is nothing to offer, and a single connection is used without being chosen.
        if (DatabaseSettingsState.getInstance().getConnections().size() < 2) return null;
        return editor -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
            panel.setText("Generic SQL: no connection chosen");
            panel.createActionLabel("Choose connection\u2026", () -> SqlConnectionChooser.chooseForFile(project, file, editorOf(editor)));
            panel.createActionLabel("Use generic SQL", () -> {
                SqlBindingsState.getInstance(project).bindFileToDialect(file, SqlDialects.ANSI.id());
                SqlEditorRefresh.refresh(project, file);
            });
            return panel;
        };
    }

    private static Function<? super FileEditor, ? extends JComponent> foundInProject(Project project, VirtualFile file, DiscoveredConnection found) {
        return editor -> {
            EditorNotificationPanel panel = new EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info);
            panel.setText(found.fileName() + ": " + found.summary());
            panel.setToolTipText(found.describe());
            panel.createActionLabel("Add connection\u2026", () -> ProjectConnectionOffer.add(project, found, file));
            if (!DatabaseSettingsState.getInstance().getConnections().isEmpty()) {
                panel.createActionLabel("Choose\u2026", () -> SqlConnectionChooser.chooseForFile(project, file, editorOf(editor)));
            }
            panel.createActionLabel("Not now", () -> {
                SqlBindingsState.getInstance(project).dismissDiscovery(found.describe());
                EditorNotifications.getInstance(project).updateNotifications(file);
            });
            return panel;
        };
    }

    private static @Nullable Editor editorOf(@Nullable FileEditor editor) { return editor instanceof TextEditor text ? text.getEditor() : null; }
}
