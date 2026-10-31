package com.segfault03.ideadb.sql.run;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/** Alt+Enter entries for working with the SQL statement under the caret. */
public final class SqlRunIntentions {
    private SqlRunIntentions() {}

    public static final class SendToConsole implements IntentionAction, DumbAware {
        @Override public @Nls @NotNull String getText() { return "Send to Console"; }
        @Override public @NotNull String getFamilyName() { return "Lattice SQL"; }
        @Override public boolean startInWriteAction() { return false; }
        @Override public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            if (editor == null || file == null || UltimateDatabasePlugin.isActive()) return false;
            return SqlRunTarget.resolve(project, editor, file) instanceof SqlRunTarget.Found found && found.target().connection() != null;
        }
        @Override public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            if (editor != null && file != null) SendToConsoleAction.sendToConsole(project, editor, file);
        }
    }
}
