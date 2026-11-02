package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.card.SqlCardDataKeys;
import org.jetbrains.annotations.NotNull;

/** Editor menu action: run the DML at the caret in a transaction that is always rolled back. */
public final class RunAndRollBackAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) { SqlRunActionSupport.update(event, SqlRunActionSupport.CardUse.ROLLBACK); }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project != null && editor != null && file != null) DryRunRunner.start(project, editor, file, event.getData(SqlCardDataKeys.SQL_CARD), DryRunRunner.Mode.ROLL_BACK);
    }
    static void runAndRollBack(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) { DryRunRunner.start(project, editor, file, DryRunRunner.Mode.ROLL_BACK); }
}
