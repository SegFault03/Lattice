package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.card.SqlCard;
import com.segfault03.ideadb.sql.card.SqlCardDataKeys;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Editor menu action: write the statement at the caret into the connection's console without running it. */
public final class SendToConsoleAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) { SqlRunActionSupport.update(event, SqlRunActionSupport.CardUse.CONSOLE); }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project != null && editor != null && file != null) sendToConsole(project, editor, file, event.getData(SqlCardDataKeys.SQL_CARD));
    }

    /** Also the entry point of the intention. */
    static void sendToConsole(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) { sendToConsole(project, editor, file, null); }

    static void sendToConsole(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file, @Nullable SqlCard card) {
        SqlRunTarget target = SqlRunActionSupport.targetOrShowProblem(project, editor, file, card, SqlRunActionSupport.CardUse.CONSOLE);
        if (target == null || target.connection() == null) return;
        SqlRunActionSupport.askValues(project, editor, target, card, "Send to Console", values -> {
            String sql = SqlLiteralRenderer.render(target.sql(), target.parameters(), values, target.connection().getType());
            SqlRunActionSupport.openConsole(project, target.connection(), sql);
        });
    }
}
