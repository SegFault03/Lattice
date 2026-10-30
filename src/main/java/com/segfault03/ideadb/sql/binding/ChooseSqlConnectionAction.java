package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import org.jetbrains.annotations.NotNull;

/** Editor menu action: choose the connection for the current SQL file. */
public final class ChooseSqlConnectionAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && file != null
                && (file.getFileType() == LatticeSqlFileType.INSTANCE || isInsideInjectedSql(event)));
    }

    /** In a Java or Kotlin file, whether the caret is in a string that is SQL. */
    private static boolean isInsideInjectedSql(AnActionEvent event) {
        var project = event.getProject();
        var editor = event.getData(CommonDataKeys.EDITOR);
        var psiFile = event.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || psiFile == null) return false;
        var injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(psiFile, editor.getCaretModel().getOffset());
        return injected != null && injected.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (event.getProject() != null && file != null) SqlConnectionChooser.chooseForFile(event.getProject(), file);
    }
}
