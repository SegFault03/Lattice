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

import java.util.Map;

/** Editor menu action: write the statement at the caret into the connection's console without running it. */
public final class SendToConsoleAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) { SqlRunActionSupport.update(event); }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project != null && editor != null && file != null) sendToConsole(project, editor, file);
    }

    /** Also the entry point of the intention. */
    static void sendToConsole(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        SqlRunTarget target = SqlRunActionSupport.targetOrShowProblem(project, editor, file);
        if (target == null || target.connection() == null) return;
        Map<String, SqlParameterValue> values = SqlRunActionSupport.askValues(project, target);
        if (values == null) return;
        String sql = SqlLiteralRenderer.render(target.sql(), target.parameters(), values, target.connection().getType());
        SqlRunActionSupport.openConsole(project, target.connection(), sql);
    }
}
