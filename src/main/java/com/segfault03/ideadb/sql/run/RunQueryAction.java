package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.card.SqlCardDataKeys;
import org.jetbrains.annotations.NotNull;

/** Run a query in the connection's console at once, with parameter values written in; a card's query, or the caret's. */
public final class RunQueryAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) { SqlRunActionSupport.update(event, SqlRunActionSupport.CardUse.RUN); }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || file == null) return;
        SqlRunTarget target = SqlRunActionSupport.targetOrShowProblem(project, editor, file, event.getData(SqlCardDataKeys.SQL_CARD),
                SqlRunActionSupport.CardUse.RUN);
        if (target == null || target.connection() == null) return;
        if (SqlStatementKind.of(target.sql(), target.profile()) != SqlStatementKind.QUERY) {
            Messages.showErrorDialog(project, SqlRunActionSupport.RUN_REFUSAL, DryRunRunner.TITLE);
            return;
        }
        SqlRunActionSupport.askValues(project, editor, target, event.getData(SqlCardDataKeys.SQL_CARD), "Run", values -> {
            String sql = SqlLiteralRenderer.render(target.sql(), target.parameters(), values, target.connection().getType());
            SqlRunActionSupport.runInConsole(project, target.connection(), sql);
        });
    }
}
