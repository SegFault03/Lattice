package com.segfault03.ideadb.sql.binding;

import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
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

    /** Whether the file has Lattice SQL injected in it, judged from the injections already computed; needs a read action. */
    public static boolean hostsSql(@NotNull Project project, @NotNull VirtualFile file) {
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
