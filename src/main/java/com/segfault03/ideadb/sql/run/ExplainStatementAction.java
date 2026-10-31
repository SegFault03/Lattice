package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** Editor menu action: show the execution plan of the statement at the caret without running it. */
public final class ExplainStatementAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) { SqlRunActionSupport.update(event); }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project != null && editor != null && file != null) explain(project, editor, file);
    }
    static void explain(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) { DryRunRunner.start(project, editor, file, DryRunRunner.Mode.EXPLAIN); }
}
