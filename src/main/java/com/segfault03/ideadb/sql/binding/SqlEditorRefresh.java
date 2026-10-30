package com.segfault03.ideadb.sql.binding;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
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
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Re-applies the dialect and highlighting settings to files that are already open, including SQL injected in code. */
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
            DaemonCodeAnalyzer.getInstance(project).restart();
        });
    }

    /** Whether the open file has Lattice SQL injected in it, judged from the injections already computed. */
    private static boolean hostsSql(Project project, VirtualFile file) {
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        if (psi == null) return false;
        for (DocumentWindow window : InjectedLanguageManager.getInstance(project).getCachedInjectedDocumentsInRange(psi, psi.getTextRange())) {
            PsiFile injected = PsiDocumentManager.getInstance(project).getPsiFile(window);
            if (injected != null && injected.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)) return true;
        }
        return false;
    }

    /** Every open SQL file, and every open source file that hosts SQL, whose dialect and checks come from the same settings. */
    public static void refreshAll(@NotNull Project project) {
        List<VirtualFile> hosts = new java.util.ArrayList<>();
        for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (file.getFileType() == LatticeSqlFileType.INSTANCE) refresh(project, file);
            else if (!file.getFileType().isBinary()) hosts.add(file);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            List<VirtualFile> withSql = hosts.stream().filter(host -> hostsSql(project, host)).toList();
            if (!withSql.isEmpty()) FileContentUtil.reparseFiles(project, withSql, true);
            DaemonCodeAnalyzer.getInstance(project).restart();
        });
    }
}
