package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotifications;
import com.intellij.util.FileContentUtil;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Re-applies the dialect and highlighting settings to files that are already open. */
public final class SqlEditorRefresh {
    private SqlEditorRefresh() {}

    public static void refresh(@NotNull Project project, @NotNull VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            FileContentUtil.reparseFiles(project, List.of(file), true);
            for (FileEditor editor : FileEditorManager.getInstance(project).getAllEditors(file)) {
                if (editor instanceof TextEditor text && text.getEditor() instanceof EditorEx ex) {
                    ex.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file));
                }
            }
            EditorNotifications.getInstance(project).updateNotifications(file);
            SqlConnectionWidget.update(project);
        });
    }

    public static void refreshAll(@NotNull Project project) {
        for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (file.getFileType() == LatticeSqlFileType.INSTANCE) refresh(project, file);
        }
    }
}
