package com.segfault03.ideadb.sql.binding;

import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.segfault03.ideadb.sql.ReadActions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import org.jetbrains.annotations.NotNull;

/** Tells whether a source file has Lattice SQL injected in it. */
public final class SqlInjectionHosts {
    private SqlInjectionHosts() {}

    /**
     * Whether the file has Lattice SQL injected in it, judged from the injections already computed. Callers are on the EDT
     * (the status bar, a refresh), which no longer holds a read lock of its own, so the read action is taken here.
     */
    public static boolean hostsSql(@NotNull Project project, @NotNull VirtualFile file) {
        return ReadActions.compute(() -> hostsSqlInReadAction(project, file));
    }

    private static boolean hostsSqlInReadAction(Project project, VirtualFile file) {
        if (!file.isValid() || file.isDirectory() || file.getFileType().isBinary()) return false;
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        if (psi == null) return false;
        for (DocumentWindow window : InjectedLanguageManager.getInstance(project).getCachedInjectedDocumentsInRange(psi, psi.getTextRange())) {
            PsiFile injected = PsiDocumentManager.getInstance(project).getPsiFile(window);
            if (injected != null && injected.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)) return true;
        }
        return false;
    }
}
